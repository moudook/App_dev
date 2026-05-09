package com.example.smarty.features.games.domain

// ─────────────────────────────────────────────────────────────────────────────
// CHESS ENGINE — Bitboard-based, zero-GC hot paths
// ─────────────────────────────────────────────────────────────────────────────
// All move generation, legality, check detection, and apply-move logic.
// Uses primitive IntArray bitboards (12 piece bbs + 2 occupancy bbs).
// Move encoding: Int = from*64 + to (0..4095). Special flags in bits 12-15.
// ─────────────────────────────────────────────────────────────────────────────

object ChessEngine {

    // ── Move flags (bits 12+) ──────────────────────────────────────────────
    const val MF_NONE   = 0
    const val MF_EP     = 1   // en passant capture
    const val MF_CASTLE = 2   // castling
    const val MF_PROMO  = 4   // promotion (always to queen for now)
    const val MF_DPUSH  = 8   // double pawn push

    fun moveFrom(m: Int) = m and 63
    fun moveTo(m: Int)   = (m shr 6) and 63
    fun moveFlags(m: Int) = m shr 12
    fun makeMove(from: Int, to: Int, flags: Int = 0) = from or (to shl 6) or (flags shl 12)

    // ── Board state (mutable, reused during search) ───────────────────────
    class State(
        val pcBbs: IntArray = IntArray(24), // 12 piece bitboards × 2 Ints each (lo32, hi32)
        val occ: IntArray = IntArray(4),    // [wLo,wHi, bLo,bHi]
        var cr: Int = CR_ALL,                  // castle rights bitmask
        var ep: Int = -1,                      // en passant target square index (-1 if none)
        var turn: Int = 0                      // 0=white, 1=black
    ) {
        fun copy(): State {
            val s = State(pcBbs.copyOf(), occ.copyOf(), cr, ep, turn)
            return s
        }
    }

    // We store Long bitboards as pairs of Ints (lo32, hi32) for zero-boxing.
    // Access helpers:
    private fun longFromPair(arr: IntArray, base: Int): Long =
        (arr[base].toLong() and 0xFFFFFFFFL) or (arr[base + 1].toLong() shl 32)

    private fun longToPair(arr: IntArray, base: Int, v: Long) {
        arr[base] = v.toInt()
        arr[base + 1] = (v ushr 32).toInt()
    }

    fun getPcBb(s: State, idx: Int): Long = longFromPair(s.pcBbs, idx * 2)
    fun setPcBb(s: State, idx: Int, v: Long) { longToPair(s.pcBbs, idx * 2, v) }
    fun getOcc(s: State, c: Int): Long = longFromPair(s.occ, c * 2)
    fun setOcc(s: State, c: Int, v: Long) { longToPair(s.occ, c * 2, v) }

    // ── Initialize from standard start position ───────────────────────────
    fun initStart(): State {
        val s = State()
        // White pawns on rank 2 (visual row 6 → bb indices 48..55)
        setPcBb(s, pcBbIdx(PT_P, 0), 0x00FF000000000000L)
        // Black pawns on rank 7 (visual row 1 → bb indices 8..15)
        setPcBb(s, pcBbIdx(PT_P, 1), 0x000000000000FF00L)
        // Rooks
        setPcBb(s, pcBbIdx(PT_R, 0), bit(56) or bit(63))  // a1,h1
        setPcBb(s, pcBbIdx(PT_R, 1), bit(0)  or bit(7))   // a8,h8
        // Knights
        setPcBb(s, pcBbIdx(PT_N, 0), bit(57) or bit(62))   // b1,g1
        setPcBb(s, pcBbIdx(PT_N, 1), bit(1)  or bit(6))    // b8,g8
        // Bishops
        setPcBb(s, pcBbIdx(PT_B, 0), bit(58) or bit(61))   // c1,f1
        setPcBb(s, pcBbIdx(PT_B, 1), bit(2)  or bit(5))    // c8,f8
        // Queens
        setPcBb(s, pcBbIdx(PT_Q, 0), bit(59))  // d1
        setPcBb(s, pcBbIdx(PT_Q, 1), bit(3))    // d8
        // Kings
        setPcBb(s, pcBbIdx(PT_K, 0), bit(60))  // e1
        setPcBb(s, pcBbIdx(PT_K, 1), bit(4))    // e8

        // Build occupancy
        var wOcc = 0L; var bOcc = 0L
        for (pt in 0..5) {
            wOcc = wOcc or getPcBb(s, pcBbIdx(pt, 0))
            bOcc = bOcc or getPcBb(s, pcBbIdx(pt, 1))
        }
        setOcc(s, 0, wOcc); setOcc(s, 1, bOcc)
        return s
    }

    // ── Square index from visual (row, file) ──────────────────────────────
    fun sq(r: Int, f: Int): Int = sqIdx(r, f)

    // ── Attack generation (no allocations) ────────────────────────────────

    // Precomputed knight attacks for each square (64 entries)
    private val KNIGHT_ATTACKS = LongArray(64)
    private val KING_ATTACKS = LongArray(64)

    init {
        for (i in 0 until 64) {
            val r = i / 8; val f = i % 8
            var kn = 0L
            val deltas = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
            for (d in deltas) {
                val nr = r + d.first; val nf = f + d.second
                if (nr in 0..7 && nf in 0..7) kn = kn or bit(nr * 8 + nf)
            }
            KNIGHT_ATTACKS[i] = kn
        }
        for (i in 0 until 64) {
            val r = i / 8; val f = i % 8
            var k = 0L
            for (dr in -1..1) for (df in -1..1) {
                if (dr == 0 && df == 0) continue
                val nr = r + dr; val nf = f + df
                if (nr in 0..7 && nf in 0..7) k = k or bit(nr * 8 + nf)
            }
            KING_ATTACKS[i] = k
        }
    }

    // Sliding attacks (rook/bishop) — simple loop, no magic bitboards yet
    private val ROOK_DIRS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val BISHOP_DIRS = arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

    private fun slidingAttacks(sq: Int, occ: Long, dirs: Array<Pair<Int, Int>>): Long {
        val r = sq / 8; val f = sq % 8
        var attacks = 0L
        for ((dr, df) in dirs) {
            var cr = r + dr; var cf = f + df
            while (cr in 0..7 && cf in 0..7) {
                val i = cr * 8 + cf
                attacks = attacks or bit(i)
                if (occ and bit(i) != 0L) break
                cr += dr; cf += df
            }
        }
        return attacks
    }

    fun rookAttacks(sq: Int, occ: Long) = slidingAttacks(sq, occ, ROOK_DIRS)
    fun bishopAttacks(sq: Int, occ: Long) = slidingAttacks(sq, occ, BISHOP_DIRS)
    fun queenAttacks(sq: Int, occ: Long) = rookAttacks(sq, occ) or bishopAttacks(sq, occ)

    // ── Is a square attacked by a given color? ────────────────────────────
    fun isAttacked(s: State, sq: Int, byCol: Int): Boolean {
        val occ = getOcc(s, 0) or getOcc(s, 1)
        // Pawn attacks
        val pBb = getPcBb(s, pcBbIdx(PT_P, byCol))
        if (byCol == 0) { // white attacks upward (from lower index)
            val r = sq / 8; val f = sq % 8
            if (r > 0) {
                if (f > 0 && pBb and bit(sq - 9) != 0L) return true
                if (f < 7 && pBb and bit(sq - 7) != 0L) return true
            }
        } else { // black attacks downward
            val r = sq / 8; val f = sq % 8
            if (r < 7) {
                if (f > 0 && pBb and bit(sq + 7) != 0L) return true
                if (f < 7 && pBb and bit(sq + 9) != 0L) return true
            }
        }
        // Knight
        if (KNIGHT_ATTACKS[sq] and getPcBb(s, pcBbIdx(PT_N, byCol)) != 0L) return true
        // King
        if (KING_ATTACKS[sq] and getPcBb(s, pcBbIdx(PT_K, byCol)) != 0L) return true
        // Rook/Queen (straight)
        val rq = getPcBb(s, pcBbIdx(PT_R, byCol)) or getPcBb(s, pcBbIdx(PT_Q, byCol))
        if (rookAttacks(sq, occ) and rq != 0L) return true
        // Bishop/Queen (diagonal)
        val bq = getPcBb(s, pcBbIdx(PT_B, byCol)) or getPcBb(s, pcBbIdx(PT_Q, byCol))
        if (bishopAttacks(sq, occ) and bq != 0L) return true
        return false
    }

    // ── Find king square ──────────────────────────────────────────────────
    fun kingSq(s: State, col: Int): Int {
        val kbb = getPcBb(s, pcBbIdx(PT_K, col))
        if (kbb == 0L) return -1
        return kbb.lsb()
    }

    fun inCheck(s: State, col: Int): Boolean {
        val ks = kingSq(s, col)
        return ks >= 0 && isAttacked(s, ks, 1 - col)
    }

    // ── Generate all pseudo-legal moves into pre-allocated array ──────────
    // Returns count of moves written to moves[] array.
    fun pseudoMoves(
        s: State,
        moves: IntArray,   // pre-allocated, size >= 256
        caps: IntArray,     // 1 if capture, 0 otherwise (parallel to moves)
        startIdx: Int = 0
    ): Int {
        var cnt = startIdx
        val col = s.turn
        val opp = 1 - col
        val myOcc = getOcc(s, col)
        val theirOcc = getOcc(s, opp)
        val allOcc = myOcc or theirOcc

        for (pt in 0..5) {
            val bb = getPcBb(s, pcBbIdx(pt, col))
            var pieces = bb
            while (pieces != 0L) {
                val from = pieces.lsb()
                pieces = pieces xor bit(from)
                val targets = when (pt) {
                    PT_P -> pawnAttacks(s, from, col, myOcc, theirOcc, allOcc)
                    PT_N -> KNIGHT_ATTACKS[from] and myOcc.inv()
                    PT_K -> KING_ATTACKS[from] and myOcc.inv()
                    PT_R -> rookAttacks(from, allOcc) and myOcc.inv()
                    PT_B -> bishopAttacks(from, allOcc) and myOcc.inv()
                    PT_Q -> queenAttacks(from, allOcc) and myOcc.inv()
                    else -> 0L
                }
                var t = targets
                while (t != 0L) {
                    val to = t.lsb()
                    t = t xor bit(to)
                    val isCap = (theirOcc and bit(to)) != 0L
                    var flags = MF_NONE
                    if (pt == PT_P) {
                        val fromR = from / 8; val toR = to / 8
                        if (toR == 0 || toR == 7) flags = MF_PROMO
                        else if (kotlin.math.abs(toR - fromR) == 2) flags = MF_DPUSH
                        // En passant
                        if (s.ep >= 0 && to == s.ep && kotlin.math.abs(from % 8 - to % 8) == 1)
                            flags = MF_EP
                    }
                    if (pt == PT_K && kotlin.math.abs(from % 8 - to % 8) == 2)
                        flags = MF_CASTLE
                    moves[cnt] = makeMove(from, to, flags)
                    caps[cnt] = if (isCap || flags == MF_EP) 1 else 0
                    cnt++
                }
            }
        }

        // Castling
        val kbb = getPcBb(s, pcBbIdx(PT_K, col))
        if (kbb != 0L && !inCheck(s, col)) {
            val ksq = kbb.lsb()
            val rank = if (col == 0) 7 else 0  // white back rank=7 (row with e1), black back rank=0 (row with e8)
            val baseSq = rank * 8 + 4
            if (ksq == baseSq) {
                // Kingside
                val ksRight = if (col == 0) (s.cr and CR_WK) != 0 else (s.cr and CR_BK) != 0
                if (ksRight) {
                    val f1 = rank * 8 + 5; val g1 = rank * 8 + 6
                    if (allOcc and bit(f1) == 0L && allOcc and bit(g1) == 0L &&
                        !isAttacked(s, f1, opp) && !isAttacked(s, g1, opp)) {
                        moves[cnt] = makeMove(baseSq, g1, MF_CASTLE)
                        caps[cnt] = 0; cnt++
                    }
                }
                // Queenside
                val qsRight = if (col == 0) (s.cr and CR_WQ) != 0 else (s.cr and CR_BQ) != 0
                if (qsRight) {
                    val d1 = rank * 8 + 3; val c1 = rank * 8 + 2; val b1 = rank * 8 + 1
                    if (allOcc and bit(d1) == 0L && allOcc and bit(c1) == 0L && allOcc and bit(b1) == 0L &&
                        !isAttacked(s, d1, opp) && !isAttacked(s, c1, opp)) {
                        moves[cnt] = makeMove(baseSq, c1, MF_CASTLE)
                        caps[cnt] = 0; cnt++
                    }
                }
            }
        }
        return cnt
    }

    private fun pawnAttacks(s: State, from: Int, col: Int, myOcc: Long, theirOcc: Long, allOcc: Long): Long {
        val r = from / 8; val f = from % 8
        var targets = 0L
        if (col == 0) { // white moves up (increasing rank index)
            // Forward one
            val one = from + 8
            if (r < 7 && allOcc and bit(one) == 0L) {
                targets = targets or bit(one)
                // Forward two from start
                if (r == 1 && allOcc and bit(from + 16) == 0L)
                    targets = targets or bit(from + 16)
            }
            // Captures
            if (f > 0) {
                val dl = from + 7
                if (theirOcc and bit(dl) != 0L || (s.ep >= 0 && dl == s.ep))
                    targets = targets or bit(dl)
            }
            if (f < 7) {
                val dr = from + 9
                if (theirOcc and bit(dr) != 0L || (s.ep >= 0 && dr == s.ep))
                    targets = targets or bit(dr)
            }
        } else { // black moves down
            val one = from - 8
            if (r > 0 && allOcc and bit(one) == 0L) {
                targets = targets or bit(one)
                if (r == 6 && allOcc and bit(from - 16) == 0L)
                    targets = targets or bit(from - 16)
            }
            if (f > 0) {
                val dl = from - 9
                if (theirOcc and bit(dl) != 0L || (s.ep >= 0 && dl == s.ep))
                    targets = targets or bit(dl)
            }
            if (f < 7) {
                val dr = from - 7
                if (theirOcc and bit(dr) != 0L || (s.ep >= 0 && dr == s.ep))
                    targets = targets or bit(dr)
            }
        }
        return targets and myOcc.inv()
    }

    // ── Apply/undo move on mutable state (zero-allocation) ────────────────
    // Undo info stored in a long: capturedPieceType(4)|capturedColor(1)|epSq(7)|prevCr(4)|flags(4)|fromCaptured(1)|extraRemoveSq(7)|rookFrom(7)|rookTo(7)
    // Simpler: use an IntArray(8) for undo info, pre-allocated.

    class UndoInfo(
        var capturedPcIdx: Int = -1,   // pcBbs index of captured piece (-1 if none)
        var capturedSq: Int = -1,       // square where captured piece was
        var extraRemoveSq: Int = -1,    // EP captured pawn square
        var rookFrom: Int = -1,         // castling rook from
        var rookTo: Int = -1,           // castling rook to
        var wasPromo: Boolean = false,
        var prevCr: Int = 0,
        var prevEp: Int = -1,
        var fromPcIdx: Int = -1         // moving piece pcBbs index
    )

    fun applyMove(s: State, move: Int, u: UndoInfo) {
        val from = moveFrom(move); val to = moveTo(move); val flags = moveFlags(move)
        u.prevCr = s.cr; u.prevEp = s.ep
        u.capturedPcIdx = -1; u.extraRemoveSq = -1
        u.rookFrom = -1; u.rookTo = -1; u.wasPromo = false

        // Find moving piece
        val col = s.turn; val opp = 1 - col
        var fromPt = -1
        for (pt in 0..5) {
            val idx = pcBbIdx(pt, col)
            if (getPcBb(s, idx) and bit(from) != 0L) { fromPt = pt; u.fromPcIdx = idx; break }
        }
        if (fromPt < 0) return

        // Find captured piece
        if (flags == MF_EP) {
            val capSq = if (col == 0) to - 8 else to + 8
            u.capturedPcIdx = pcBbIdx(PT_P, opp)
            u.capturedSq = capSq
            u.extraRemoveSq = capSq
            setPcBb(s, u.capturedPcIdx, getPcBb(s, u.capturedPcIdx) xor bit(capSq))
            setOcc(s, opp, getOcc(s, opp) xor bit(capSq))
        } else {
            for (pt in 0..5) {
                val idx = pcBbIdx(pt, opp)
                if (getPcBb(s, idx) and bit(to) != 0L) {
                    u.capturedPcIdx = idx; u.capturedSq = to
                    setPcBb(s, idx, getPcBb(s, idx) xor bit(to))
                    setOcc(s, opp, getOcc(s, opp) xor bit(to))
                    break
                }
            }
        }

        // Castling rook
        if (flags == MF_CASTLE) {
            val rank = from / 8
            if (to % 8 == 6) { // kingside
                u.rookFrom = rank * 8 + 7; u.rookTo = rank * 8 + 5
            } else { // queenside
                u.rookFrom = rank * 8 + 0; u.rookTo = rank * 8 + 3
            }
            val rIdx = pcBbIdx(PT_R, col)
            setPcBb(s, rIdx, getPcBb(s, rIdx) xor bit(u.rookFrom) or bit(u.rookTo))
            val myOcc = getOcc(s, col)
            setOcc(s, col, myOcc xor bit(u.rookFrom) or bit(u.rookTo))
        }

        // Move the piece
        setPcBb(s, u.fromPcIdx, getPcBb(s, u.fromPcIdx) xor bit(from) or bit(to))
        val myOcc = getOcc(s, col)
        setOcc(s, col, myOcc xor bit(from) or bit(to))

        // Promotion
        if (flags == MF_PROMO) {
            u.wasPromo = true
            setPcBb(s, u.fromPcIdx, getPcBb(s, u.fromPcIdx) xor bit(to)) // remove pawn
            val qIdx = pcBbIdx(PT_Q, col)
            setPcBb(s, qIdx, getPcBb(s, qIdx) or bit(to)) // add queen
        }

        // En passant target
        s.ep = if (flags == MF_DPUSH) {
            val epSq = if (col == 0) from + 8 else from - 8
            epSq
        } else -1

        // Castle rights update
        var cr = s.cr
        if (fromPt == PT_K) {
            cr = if (col == 0) cr and (CR_ALL xor (CR_WK or CR_WQ))
                 else cr and (CR_ALL xor (CR_BK or CR_BQ))
        }
        if (fromPt == PT_R) {
            if (from == 56) cr = cr and CR_WQ.inv() and 0xF // a1 = white queenside rook
            if (from == 63) cr = cr and CR_WK.inv() and 0xF // h1 = white kingside rook
            if (from == 0)  cr = cr and CR_BQ.inv() and 0xF // a8 = black queenside rook
            if (from == 7)  cr = cr and CR_BK.inv() and 0xF // h8 = black kingside rook
        }
        // Captured rook
        if (u.capturedPcIdx == pcBbIdx(PT_R, opp)) {
            if (u.capturedSq == 56) cr = cr and (0xF xor CR_WQ) // a1
            if (u.capturedSq == 63) cr = cr and (0xF xor CR_WK) // h1
            if (u.capturedSq == 0)  cr = cr and (0xF xor CR_BQ) // a8
            if (u.capturedSq == 7)  cr = cr and (0xF xor CR_BK) // h8
        }
        s.cr = cr and 0xF

        s.turn = opp
    }

    fun undoMove(s: State, move: Int, u: UndoInfo) {
        val from = moveFrom(move); val to = moveTo(move)
        val col = 1 - s.turn  // original mover
        s.turn = col
        s.cr = u.prevCr; s.ep = u.prevEp

        // Undo promotion first (turn queen back to pawn at 'to')
        if (u.wasPromo) {
            val qIdx = pcBbIdx(PT_Q, col)
            setPcBb(s, qIdx, getPcBb(s, qIdx) xor bit(to))
            // restore pawn at 'to' will be handled by move-undo below
        }

        // Undo castling rook
        if (u.rookFrom >= 0) {
            val rIdx = pcBbIdx(PT_R, col)
            setPcBb(s, rIdx, getPcBb(s, rIdx) xor bit(u.rookTo) or bit(u.rookFrom))
            setOcc(s, col, getOcc(s, col) xor bit(u.rookTo) or bit(u.rookFrom))
        }

        // Move piece back
        setPcBb(s, u.fromPcIdx, getPcBb(s, u.fromPcIdx) xor bit(to) or bit(from))
        setOcc(s, col, getOcc(s, col) xor bit(to) or bit(from))

        // Restore captured piece
        if (u.capturedPcIdx >= 0) {
            setPcBb(s, u.capturedPcIdx, getPcBb(s, u.capturedPcIdx) or bit(u.capturedSq))
            setOcc(s, 1 - col, getOcc(s, 1 - col) or bit(u.capturedSq))
        }
        // EP capture: clear the target square in occupancy
        if (u.extraRemoveSq >= 0) {
            setOcc(s, 1 - col, getOcc(s, 1 - col) xor bit(u.capturedSq))
        }
    }

    // ── Legal move generation ─────────────────────────────────────────────
    fun legalMoves(s: State, moves: IntArray, caps: IntArray): Int {
        val pseudo = pseudoMoves(s, moves, caps, 0)
        var legal = 0
        val u = UndoInfo()
        for (i in 0 until pseudo) {
            val m = moves[i]
            applyMove(s, m, u)
            // After apply, s.turn is opponent. Check if OUR king is safe.
            if (!inCheck(s, 1 - s.turn)) {
                moves[legal] = m; caps[legal] = caps[i]; legal++
            }
            undoMove(s, m, u)
        }
        return legal
    }

    fun anyLegalMove(s: State): Boolean {
        val moves = IntArray(256); val caps = IntArray(256)
        return legalMoves(s, moves, caps) > 0
    }

    // ── Convert engine state to legacy Board for UI ──────────────────────
    fun toBoard(s: State): Board {
        val b = mutableListOf<Pc?>()
        repeat(64) { b.add(null) }
        for (pt in 0..5) {
            for (col in 0..1) {
                val bb = getPcBb(s, pcBbIdx(pt, col))
                var pieces = bb
                while (pieces != 0L) {
                    val idx = pieces.lsb()
                    pieces = pieces xor bit(idx)
                    val r = idxToRow(idx); val f = idxToFile(idx)
                    b[r * 8 + f] = Pc(typFromPt(pt), colFromInt(col))
                }
            }
        }
        return b
    }

    private fun typFromPt(pt: Int) = when (pt) {
        PT_P -> Typ.P; PT_N -> Typ.N; PT_B -> Typ.B
        PT_R -> Typ.R; PT_Q -> Typ.Q; PT_K -> Typ.K
        else -> Typ.P
    }
    private fun colFromInt(c: Int) = if (c == 0) Col.W else Col.B

    // ── CastleRights conversion ──────────────────────────────────────────
    fun crToRights(cr: Int): CastleRights = CastleRights(
        wKingside = (cr and CR_WK) != 0, wQueenside = (cr and CR_WQ) != 0,
        bKingside = (cr and CR_BK) != 0, bQueenside = (cr and CR_BQ) != 0
    )
    fun rightsToCr(r: CastleRights): Int =
        (if (r.wKingside) CR_WK else 0) or (if (r.wQueenside) CR_WQ else 0) or
        (if (r.bKingside) CR_BK else 0) or (if (r.bQueenside) CR_BQ else 0)
}
