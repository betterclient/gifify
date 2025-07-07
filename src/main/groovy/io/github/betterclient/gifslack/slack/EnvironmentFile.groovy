package io.github.betterclient.gifslack.slack

import java.nio.charset.Charset

class EnvironmentFile {
    String getAt(String input) {
        InputStream r = EnvironmentFile.class.getResourceAsStream("/.env")?: new ByteArrayInputStream("N/A".getBytes(Charset.defaultCharset()))
        def str = new String(r.readAllBytes())
        r.close()

        if (str == "N/A") {
            return ""
        } else {
            def split = str
                    .split("\n")
                    *.split("=")

            return split.find {
                it[0].replace("\n", "").replace("\r", "") == input
            }
                    .drop(1)
                    .collect { it.replace("\n", "").replace("\r", "") }
                    .join("=")
        }
    }
}
