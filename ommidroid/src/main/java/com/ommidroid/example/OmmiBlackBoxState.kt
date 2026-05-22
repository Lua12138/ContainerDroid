package com.ommidroid.example

object OmmiBlackBoxState {
    @Volatile
    var initializationErrorMessage: String? = null

    fun markInitializationFailure(message: String) {
        initializationErrorMessage = message
    }

    fun clearInitializationFailure() {
        initializationErrorMessage = null
    }
}
