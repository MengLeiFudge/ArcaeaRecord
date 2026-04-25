package arc.record.funcs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static arc.record.Settings.ARC_DIR;

/**
 * 从 lowiro 官方接口下载最新的 Arcaea c 版安装包。
 */
public class DownloadLatestApk {
    private static final URI APK_API =
            URI.create("https://webapi.lowiro.com/webapi/serve/static/bin/arcaea/apk");
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Path downloadLatest() throws IOException, InterruptedException {
        return downloadLatest(ARC_DIR.toPath());
    }

    public Path downloadLatestForConsole() throws IOException, InterruptedException {
        return downloadLatest(ARC_DIR.toPath(), false);
    }

    public Path downloadLatest(Path targetDir) throws IOException, InterruptedException {
        return downloadLatest(targetDir, true);
    }

    private Path downloadLatest(Path targetDir, boolean emitMachineProgress) throws IOException, InterruptedException {
        Files.createDirectories(targetDir);

        ApkInfo apkInfo = fetchLatestApkInfo();
        String fileName = resolveFileName(apkInfo);
        Path targetFile = targetDir.resolve(fileName);
        if (Files.isRegularFile(targetFile) && Files.size(targetFile) > 0) {
            System.out.println("安装包已存在，跳过下载：" + targetFile);
            printProgress(100, Files.size(targetFile), Files.size(targetFile), emitMachineProgress);
            return targetFile;
        }

        Path tempFile = targetDir.resolve(fileName + ".part");
        Files.deleteIfExists(tempFile);

        HttpRequest request = HttpRequest.newBuilder(URI.create(apkInfo.url()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "arcaeaRecord/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(tempFile);
            throw new IOException("下载失败，HTTP " + response.statusCode());
        }
        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        printProgress(0, 0, totalBytes, emitMachineProgress);
        int nextPercent = 10;
        try (
                InputStream input = response.body();
                OutputStream output = Files.newOutputStream(tempFile)
        ) {
            byte[] buffer = new byte[1024 * 1024];
            long downloadedBytes = 0;
            int readLength;
            while ((readLength = input.read(buffer)) >= 0) {
                output.write(buffer, 0, readLength);
                downloadedBytes += readLength;
                if (totalBytes > 0) {
                    int percent = (int) Math.min(100, downloadedBytes * 100 / totalBytes);
                    if (percent >= nextPercent) {
                        printProgress(percent, downloadedBytes, totalBytes, emitMachineProgress);
                        nextPercent += 10;
                    }
                }
            }
            if (totalBytes <= 0) {
                printProgress(100, downloadedBytes, downloadedBytes, emitMachineProgress);
            } else if (downloadedBytes < totalBytes) {
                throw new IOException("下载不完整：" + downloadedBytes + "/" + totalBytes);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
        if (totalBytes > 0 && nextPercent <= 100) {
            printProgress(100, totalBytes, totalBytes, emitMachineProgress);
        }
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("下载完成：" + targetFile);
        return targetFile;
    }

    private static void printProgress(
            int percent,
            long downloadedBytes,
            long totalBytes,
            boolean emitMachineProgress
    ) {
        System.out.println("下载进度：" + percent + "%（"
                + formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes) + "）");
        if (emitMachineProgress) {
            System.out.println("APK_PROGRESS " + percent + " " + downloadedBytes + " " + totalBytes);
        }
        System.out.flush();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        double mib = bytes / 1024.0 / 1024.0;
        if (mib >= 1024) {
            return String.format("%.2f GiB", mib / 1024.0);
        }
        return String.format("%.1f MiB", mib);
    }

    private ApkInfo fetchLatestApkInfo() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(APK_API)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "arcaeaRecord/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("获取安装包信息失败，HTTP " + response.statusCode());
        }

        String body = response.body();
        String url = matchRequired(URL_PATTERN, body, "url");
        String version = matchRequired(VERSION_PATTERN, body, "version");
        return new ApkInfo(url, version);
    }

    private static String resolveFileName(ApkInfo apkInfo) {
        URI uri = URI.create(apkInfo.url());
        String query = uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                int splitIndex = part.indexOf('=');
                if (splitIndex <= 0) {
                    continue;
                }
                String key = URLDecoder.decode(part.substring(0, splitIndex), StandardCharsets.UTF_8);
                if (!key.equals("filename")) {
                    continue;
                }
                String value = URLDecoder.decode(part.substring(splitIndex + 1), StandardCharsets.UTF_8);
                if (value.matches("(?i)arc.*\\.apk")) {
                    return value;
                }
            }
        }
        return "arcaea_" + apkInfo.version() + ".apk";
    }

    private static String matchRequired(Pattern pattern, String body, String fieldName) throws IOException {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IOException("安装包信息缺少字段：" + fieldName);
        }
        return matcher.group(1);
    }

    private record ApkInfo(String url, String version) {
    }
}
