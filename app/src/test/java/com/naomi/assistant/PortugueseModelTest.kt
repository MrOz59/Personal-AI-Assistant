package com.naomi.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortugueseModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun model(vararg files: String): File = tmp.newFolder().also { dir ->
        files.forEach { File(dir, it).apply { parentFile?.mkdirs() }.writeText("x") }
    }

    @Test
    fun takesBothVoskLayouts() {
        assertTrue(PortugueseModel.isModel(model("am/final.mdl", "conf/model.conf", "graph/Gr.fst")))
        // vosk-model-small-pt-0.3, the one downloaded: everything at the top.
        assertTrue(PortugueseModel.isModel(model("final.mdl", "mfcc.conf", "Gr.fst", "HCLr.fst", "ivector/final.ie")))
        assertFalse(PortugueseModel.isModel(model("README")))
        assertFalse(PortugueseModel.isModel(model("final.mdl")))
    }
}
