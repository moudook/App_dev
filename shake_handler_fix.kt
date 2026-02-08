    /**
     * Handle shake gesture contextually
     * Two distinct behaviors:
     * 1. Toggle chat mode - ONLY when input is completely empty AND mic is not active
     * 2. Toggle privacy mode - when mic is active OR has text/attachments
     */
    private fun handleShake() {
        when {
            // Priority 1: During share flow -> toggle full privacy mode
            shareFlowManager.isInShareMode() -> {
                toggleShareFullPrivacy()
                Log.d(TAG, "Shake: Toggled full privacy mode during share")
            }
            // Priority 2: Has text OR attachments -> toggle privacy mode
            _currentInputText.value.isNotBlank() || _currentInputAttachments.value.isNotEmpty() -> {
                togglePendingNoteAiExclusion()
                Log.d(TAG, "Shake: Toggled privacy mode (has input)")
            }
            // Priority 3: No text AND no attachments AND mic not active -> toggle chat mode
            else -> {
                toggleChatMode()
                Log.d(TAG, "Shake: Toggled chat mode (empty input, mic not active)")
            }
        }
    }
