package com.claudetabs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeIntegrationDeployerTest {

    @Test fun shFilesGetCrlfStripped() {
        val crlf = "#!/bin/bash\r\necho hello\r\n".toByteArray(Charsets.UTF_8)
        val out = ClaudeIntegrationDeployer.normalizeIfText("claude-integration/rename-tab.sh", crlf)
        assertEquals("#!/bin/bash\necho hello\n", String(out, Charsets.UTF_8))
    }

    @Test fun mdFilesGetCrlfStripped() {
        val crlf = "# Heading\r\n\r\nbody\r\n".toByteArray(Charsets.UTF_8)
        val out = ClaudeIntegrationDeployer.normalizeIfText("commands/tab.md", crlf)
        assertEquals("# Heading\n\nbody\n", String(out, Charsets.UTF_8))
    }

    @Test fun bareLfIsPreserved() {
        val lf = "#!/bin/bash\necho hello\n".toByteArray(Charsets.UTF_8)
        val out = ClaudeIntegrationDeployer.normalizeIfText("foo.sh", lf)
        assertArrayEquals(lf, out)
    }

    @Test fun unknownExtensionBytesAreUntouched() {
        // Pretend-binary blob containing a CR-LF that must NOT be rewritten.
        val bin = byteArrayOf(0x00, 0x0d, 0x0a, 0x7f, 0xff.toByte())
        val out = ClaudeIntegrationDeployer.normalizeIfText("payload.bin", bin)
        assertArrayEquals(bin, out)
    }

    @Test fun extensionMatchIsCaseInsensitive() {
        val crlf = "foo\r\nbar\r\n".toByteArray(Charsets.UTF_8)
        val out = ClaudeIntegrationDeployer.normalizeIfText("FOO.SH", crlf)
        assertEquals("foo\nbar\n", String(out, Charsets.UTF_8))
    }
}
