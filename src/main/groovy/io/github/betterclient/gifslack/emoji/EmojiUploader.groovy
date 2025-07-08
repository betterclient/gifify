package io.github.betterclient.gifslack.emoji

import groovy.transform.CompileStatic
import io.github.betterclient.gifslack.Main
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

import java.nio.file.Files
import java.nio.file.Path

class EmojiUploader {
    static Stack<Closure> emojiQueue = new Stack<>()

    static void uploadEmoji(String name, byte[] content) {
        try {
            uploadEmoji0(name, content, Main.environment["EMOJI_ADD_TOKEN"], Main.environment["EMOJI_ADD_COOKIE_HEADER"], 2)
        } catch(Exception ignored) {
            Thread.sleep(500)
            uploadEmoji0(name, content, Main.environment["EMOJI_ADD_TOKEN"], Main.environment["EMOJI_ADD_COOKIE_HEADER"], 2)
        }
    }

    private static void uploadEmoji0(String name, byte[] gifBytes, String token, String cookieHeader, int optimizationAmount) {
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
            uploadEmoji0(name, gifBytes, token, cookieHeader, optimizationAmount) //FUCK YOUR RATE LIMIT
            return
        } else if (resp.contains("resized_but_still_too_large")) {
            resp += "Retrying..."
            println(resp)
            Thread.sleep(500)
            uploadEmoji0(name, optimizeGifWithGifsicle(gifBytes, optimizationAmount + 1), token, cookieHeader, optimizationAmount + 1) //FUCK YOU
            return
        }
        println(resp)
    }

    @CompileStatic
    static byte[] optimizeGifWithGifsicle(byte[] inputGif, int optimizationAmount) {
        println("Optimizing gif with gifsicle, optimization level: $optimizationAmount...")
        Path tempInput = null
        Path tempOutput = null

        try {
            tempInput = Files.createTempFile("input-", ".gif")
            tempOutput = Files.createTempFile("output-", ".gif")

            Files.write(tempInput, inputGif)

            String path = Main.environment.get("GIFSICLE_FULL_PATH")
            def args = [
                    path,
                    "--optimize=$optimizationAmount",
                    tempInput.toString(),
                    "-o",
                    tempOutput.toString()
            ]

            //optimizations
            if (optimizationAmount >= 4) args.add(1, "--lossy=80") //Optimization failed once, enable lossy(risky??) compression
            if (optimizationAmount >= 5) args.addAll(["--colors", "1024"]) //Optimization failed twice(how???), reduce colors
            if (optimizationAmount >= 6) args.set(args.indexOf("1024"), "256") //Optimization failed three(HOW?????) times, reduce colors even more
            if (optimizationAmount >= 7) args.addAll(1, ["--scale", "0.5"]) //Optimization failed FOUR(WTF?????????) times, reduce scale
            if (optimizationAmount >= 8) args.set(args.indexOf("--lossy=80"), "--lossy=100") //Optimization failed FIVE times, maximize loss
            //if optimization fails more than this... idk what to do, fuck slack i guess
            if (optimizationAmount >= 10) throw new RuntimeException("Gif still too large after optimizing 10 times")

            def pb = new ProcessBuilder(args*.toString())

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