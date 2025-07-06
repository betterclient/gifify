package io.github.betterclient.gifslack

import com.madgag.gif.fmsware.AnimatedGifEncoder
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

import java.nio.file.Files
import java.nio.file.Path

class GifManager {
    static Map<String, AnimatedGifEncoder> encoderMap = new HashMap<>();
    static Map<String, ByteArrayOutputStream> outputMap = new HashMap<>();

    static AnimatedGifEncoder getEncoderFor(String key) {
        return encoderMap.computeIfAbsent(key, k -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream()
            outputMap.put(k, out)

            AnimatedGifEncoder encoder = new AnimatedGifEncoder()
            encoder.start(out)
            encoder.setRepeat(0)
            return encoder
        })
    }

    static ByteArrayOutputStream getOutputStreamFor(String key) {
        return outputMap.get(key)
    }

    static Stack<Closure> gifQueue = new Stack<>()

    static void addGif(String name, byte[] content) {
        try {
            uploadEmoji(name, content, Main.environment["EMOJI_ADD_TOKEN"], Main.environment["EMOJI_ADD_COOKIE_HEADER"], 2)
        } catch(Exception ignored) {
            Thread.sleep(500)
            uploadEmoji(name, content, Main.environment["EMOJI_ADD_TOKEN"], Main.environment["EMOJI_ADD_COOKIE_HEADER"], 2)
        }
    }

    static void uploadEmoji(String name, byte[] gifBytes, String token, String cookieHeader, int optimizationAmount) {
        OkHttpClient client = new OkHttpClient()

        MediaType mediaType = MediaType.parse("image/gif")
        RequestBody gifBody = RequestBody.create(mediaType, gifBytes)

        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("token", token)
                .addFormDataPart("name", name)
                .addFormDataPart("mode", "data")
                .addFormDataPart("image", "emoji.gif", gifBody)
                .build()

        Request request = new Request.Builder()
                .url("https://hackclub.slack.com/api/emoji.add")
                .post(body)
                .addHeader("accept", "*/*")
                .addHeader("content-type", "multipart/form-data")
                .addHeader("cookie", cookieHeader)
                .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                .build()

        Response response = client.newCall(request).execute()
        var resp = response.body().string()
        if (resp.contains("ratelimited")) {
            resp += "Retrying..."
            println(resp)
            Thread.sleep(500)
            uploadEmoji(name, gifBytes, token, cookieHeader, optimizationAmount) //FUCK YOUR RATE LIMIT
            return
        } else if (resp.contains("resized_but_still_too_large")) {
            resp += "Retrying..."
            println(resp)
            Thread.sleep(500)
            uploadEmoji(name, optimizeGifWithGifsicle(gifBytes, optimizationAmount + 1), token, cookieHeader, optimizationAmount + 1) //FUCK YOU
            return
        }
        println(resp)
    }

    static byte[] optimizeGifWithGifsicle(byte[] inputGif, int optimizationAmount) {
        println("Optimizing gif with gifsicle...")
        Path tempInput = null
        Path tempOutput = null

        try {
            tempInput = Files.createTempFile("input-", ".gif")
            tempOutput = Files.createTempFile("output-", ".gif")

            Files.write(tempInput, inputGif)


            def pb = new ProcessBuilder(
                    "D:\\DOWNLOADS\\gifsicle.exe",
                    "--optimize=$optimizationAmount",
                    "--colors", "256",
                    tempInput.toString(),
                    "-o",
                    tempOutput.toString()
            )

            pb.redirectErrorStream(true)
            def process = pb.start()

            process.inputStream.withReader { reader ->
                reader.eachLine { line ->

                }
            }

            def exitCode = process.waitFor()
            if (exitCode != 0) {
                throw new IOException("gifsicle exited with code $exitCode")
            }

            return Files.readAllBytes(tempOutput)
        } finally {
            if (tempInput) Files.deleteIfExists(tempInput)
            if (tempOutput) Files.deleteIfExists(tempOutput)
        }
    }
}