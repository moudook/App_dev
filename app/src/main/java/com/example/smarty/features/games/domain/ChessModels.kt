package com.example.smarty.features.games.domain

// ─────────────────────────────────────────────────────────────────────────────
// CHESS MODEL - NAMING CONVENTION & ABBREVIATIONS
// ─────────────────────────────────────────────────────────────────────────────
//
// BITBOARD REPRESENTATION (primary, used by engine + AI):
//   Bb    = Long (64-bit bitboard). Bit i = square i (a1=0, h8=63).
//           Row-major: index = (7-r)*8+f where r is visual row (0=top).
//   PcBbs = IntArray(12) — one bitboard per piece-color combo.
//           Index: pieceType*2 + color where P=0,N=1,B=2,R=3,Q=4,K=5, W=0,B=1
//   Occ   = IntArray(2) — occupancy bitboards per color [W, B]
//
// LEGACY OBJECT MODEL (used only by UI for rendering):
//   Typ  = Piece Type enum (K,Q,R,B,N,P)
//   Col  = Color enum (W, B)
//   Pc   = Piece data class (t: Typ, c: Col)
//   Sq   = Square data class (r: row 0-7, f: file 0-7)
//   Board = List<Pc?> (64-element list, index = r*8+f)
//
// PARAMETER NAMING:
//   bd      = board parameter (Board/Bd type)
//   r       = row (0=top/black backrank, 7=bottom/white backrank)
//   f       = file (0=a, 7=h)
//   pc      = piece variable (Pc type)
//   sq      = square variable (Sq type)
//   from    = move origin square (Sq or Int index)
//   to      = move destination square (Sq or Int index)
//   ep      = en passant target square (Sq? nullable) or Int (-1 if none)
//   cr      = castle rights (CastleRights or Int bitmask)
//   dir     = direction (-1 for black/down, +1 for white/up)
//
// COMMON VARIABLES:
//   nb      = new board (after move applied)
//   newEp   = new en passant target
//   captured = captured piece (Pc?)
//   moves   = list of legal/pseudo moves
//   s       = state (ChessState)
//   res     = result list accumulator
// ─────────────────────────────────────────────────────────────────────────────

// ── Legacy object model (UI rendering only) ─────────────────────────────────

enum class Typ { K, Q, R, B, N, P }

enum class Col { W, B }

data class Pc(
    val t: Typ,
    val c: Col,
)

data class Sq(
    val r: Int,
    val f: Int,
) // row 0=top(black backrank), file 0=a

typealias Board = List<Pc?>

fun Board.at(
    r: Int,
    f: Int,
): Pc? = this[r * 8 + f]

fun Board.at(sq: Sq): Pc? = at(sq.r, sq.f)

fun Board.set(
    sq: Sq,
    p: Pc?,
): Board = toMutableList().also { it[sq.r * 8 + sq.f] = p }

fun Board.move(
    from: Sq,
    to: Sq,
): Board = set(from, null).set(to, at(from))

fun ok(
    r: Int,
    f: Int,
) = r in 0..7 && f in 0..7

fun opp(c: Col) = if (c == Col.W) Col.B else Col.W

data class CastleRights(
    val wKingside: Boolean = true,
    val wQueenside: Boolean = true,
    val bKingside: Boolean = true,
    val bQueenside: Boolean = true,
)

fun startBoard(): Board {
    val b = mutableListOf<Pc?>()
    repeat(64) { b.add(null) }
    val back = listOf(Typ.R, Typ.N, Typ.B, Typ.Q, Typ.K, Typ.B, Typ.N, Typ.R)
    for (f in 0..7) {
        b[0 * 8 + f] = Pc(back[f], Col.B)
        b[1 * 8 + f] = Pc(Typ.P, Col.B)
        b[6 * 8 + f] = Pc(Typ.P, Col.W)
        b[7 * 8 + f] = Pc(back[f], Col.W)
    }
    return b
}

// ── Bitboard constants & helpers ────────────────────────────────────────────

typealias Bb = Long

const val FILE_A: Bb = 0x0101010101010101L
const val FILE_B: Bb = 0x0202020202020202L
const val FILE_G: Bb = 0x4040404040404040L
const val FILE_H: Bb = -9187201950435737472L // 0x8080808080808080L
const val RANK_1: Bb = 0x00000000000000FFL
const val RANK_2: Bb = 0x000000000000FF00L
const val RANK_7: Bb = 0x00FF000000000000L
const val RANK_8: Bb = -72057594037927936L // 0xFF00000000000000L
const val FULL: Bb = -1L

// Piece-type indices for PcBbs array: [PW, PB, NW, NB, BW, BB, RW, RB, QW, QB, KW, KB]
const val PT_P = 0
const val PT_N = 1
const val PT_B = 2
const val PT_R = 3
const val PT_Q = 4
const val PT_K = 5

fun pcBbIdx(
    pt: Int,
    col: Int,
): Int = pt * 2 + col // 0..11

// Square index helpers (bitboard index = (7-row)*8+file, i.e. a1=0, h8=63)
fun sqIdx(
    r: Int,
    f: Int,
): Int = (7 - r) * 8 + f

fun sqBb(i: Int): Bb = 1L shl i

fun bit(i: Int): Bb = 1L shl i

fun Bb.popCount(): Int = java.lang.Long.bitCount(this)

fun Bb.lsb(): Int = java.lang.Long.numberOfTrailingZeros(this)

fun Bb.popLsb(): Pair<Bb, Int> {
    val i = lsb()
    return this xor bit(i) to i
}

// Castle rights bitmask (engine uses Int, UI uses CastleRights data class)
const val CR_WK = 1 // white kingside
const val CR_WQ = 2 // white queenside
const val CR_BK = 4 // black kingside
const val CR_BQ = 8 // black queenside
const val CR_ALL = 15

// Convert between visual (r,f) and bitboard index
fun idxToRow(i: Int): Int = 7 - (i / 8)

fun idxToFile(i: Int): Int = i % 8
