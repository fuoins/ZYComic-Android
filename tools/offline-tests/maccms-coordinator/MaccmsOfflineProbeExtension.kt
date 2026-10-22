package com.zycomic.app.net

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** Gives every selected runtime test an offline transport, including unchanged app tests. */
class MaccmsOfflineProbeExtension : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        MaccmsProbeRecorder.install()
    }
}
