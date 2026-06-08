package com.madhav.scanner.core.model

/**
 * The auto-capture state machine (DESIGN.md §4.5). A manual shutter can jump straight to
 * CAPTURING from any state — auto-capture is an accelerator, never a gate.
 */
enum class ScanState {
    SEARCHING,
    ALIGNING,
    STABLE,
    CAPTURING,
    RECOGNIZE,
    PARSING,
    RESULT,
}
