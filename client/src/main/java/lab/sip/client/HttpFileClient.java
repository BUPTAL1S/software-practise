package lab.sip.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for uploading/downloading files and reporting/querying CDR
 * through the Spring Boot backend (POST /api/files, POST /api/cdr, GET /api/cdr, etc.).
 */
public final class HttpFileClient {
  public record CdrRecord(
      Long id,
      String caller,
      String calleeOrRoom,
      Instant startedAt,
      long durationSec
  ) {}

  public record CdrStats(
      long totalCalls,
      long totalDurationSec,
      double averageDurationSec,
      int peakRoomMembers
  ) {}

  public record RoomInfo(
      Long id,
      String name,
      String sipUri,
      int maxMembers,
      int memberCount
  ) {}

  public record RoomMessageInfo(
      long id,
      String roomUri,
      String sender,
      String content,
      boolean isFile,
      Instant timestamp
  ) {}

  public record UserSummary(
      Long id,
      String username,
      String sipId,
      String avatarUrl
  ) {}

  private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
  private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern NAME_PATTERN = Pattern.compile("\"(?:name|fileName|filename)\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern SIZE_PATTERN = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");
  private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

  private static final Pattern CALLER_PATTERN = Pattern.compile("\"caller\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern CALLEE_PATTERN = Pattern.compile("\"(?:callee_or_room|calleeOrRoom)\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern STARTED_AT_PATTERN = Pattern.compile("\"(?:started_at|startedAt)\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern DURATION_PATTERN = Pattern.compile("\"(?:duration_sec|durationSec)\"\\s*:\\s*(\\d+)");

  private static final Pattern TOTAL_CALLS_PATTERN = Pattern.compile("\"totalCalls\"\\s*:\\s*(\\d+)");
  private static final Pattern TOTAL_DURATION_PATTERN = Pattern.compile("\"totalDurationSec\"\\s*:\\s*(\\d+)");
  private static final Pattern AVG_DURATION_PATTERN = Pattern.compile("\"averageDurationSec\"\\s*:\\s*([0-9.]+)");
  private static final Pattern PEAK_MEMBERS_PATTERN = Pattern.compile("\"peakRoomMembers\"\\s*:\\s*(\\d+)");

  private static final Pattern ROOM_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
  private static final Pattern ROOM_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern ROOM_SIP_URI_PATTERN = Pattern.compile("\"(?:sip_uri|sipUri)\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern ROOM_MAX_MEMBERS_PATTERN = Pattern.compile("\"(?:max_members|maxMembers)\"\\s*:\\s*(\\d+)");
  private static final Pattern ROOM_MEMBER_COUNT_PATTERN = Pattern.compile("\"(?:member_count|memberCount|peak_members|peakMembers)\"\\s*:\\s*(\\d+)");
  private static final Pattern AVATAR_URL_PATTERN = Pattern.compile("\"avatarUrl\"\\s*:\\s*\"([^\"]+)\"");

  private final HttpClient httpClient;
  private volatile String baseUrl;
  private volatile String authToken;
  private volatile String userAvatarUrl;

  public HttpFileClient(String baseUrl) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
  }

  public String getAuthToken() {
    return authToken;
  }

  public void setAuthToken(String authToken) {
    this.authToken = authToken;
  }

  public String getUserAvatarUrl() {
    return userAvatarUrl;
  }

  public void setUserAvatarUrl(String userAvatarUrl) {
    this.userAvatarUrl = userAvatarUrl;
  }

  public String uploadAvatar(Path imagePath, String user) throws IOException, InterruptedException {
    byte[] fileBytes = Files.readAllBytes(imagePath);
    String filename = imagePath.getFileName().toString();
    return uploadAvatar(fileBytes, filename, user);
  }

  public String uploadAvatar(byte[] fileBytes, String filename, String user) throws IOException, InterruptedException {
    String boundary = "Boundary-" + System.currentTimeMillis();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
    baos.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
    baos.write(fileBytes);
    baos.write("\r\n".getBytes(StandardCharsets.UTF_8));

    if (user != null && !user.isBlank()) {
      baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
      baos.write(("Content-Disposition: form-data; name=\"user\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      baos.write(user.trim().getBytes(StandardCharsets.UTF_8));
      baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/users/avatar"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()));

    if (authToken != null && !authToken.isBlank()) {
      builder.header("Authorization", "Bearer " + authToken);
    }

    HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() == 200) {
      Matcher m = AVATAR_URL_PATTERN.matcher(resp.body());
      if (m.find()) {
        String url = m.group(1);
        this.userAvatarUrl = url;
        return url;
      }
      return "";
    } else {
      throw new IOException("上传头像失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
  }

  public boolean resetAvatar(String user) throws IOException, InterruptedException {
    String url = baseUrl + "/api/users/avatar" + (user != null && !user.isBlank() ? "?user=" + URLEncoder.encode(user.trim(), StandardCharsets.UTF_8) : "");
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .DELETE();

    if (authToken != null && !authToken.isBlank()) {
      builder.header("Authorization", "Bearer " + authToken);
    }

    HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() == 200) {
      this.userAvatarUrl = null;
      return true;
    }
    return false;
  }

  public boolean toggleRoomVmute(String roomUri, String sipId, boolean mute) {
    try {
      String cleanRoom = (roomUri != null && roomUri.contains("@")) ? roomUri.substring(0, roomUri.indexOf('@')) : roomUri;
      if (cleanRoom != null && cleanRoom.startsWith("sip:")) cleanRoom = cleanRoom.substring(4);
      String url = baseUrl + "/api/rooms/" + URLEncoder.encode(cleanRoom != null ? cleanRoom : "", StandardCharsets.UTF_8)
          + "/vmute?sipId=" + URLEncoder.encode(sipId != null ? sipId : "", StandardCharsets.UTF_8) + "&mute=" + mute;

      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(3))
              .POST(HttpRequest.BodyPublishers.noBody());

      if (authToken != null && !authToken.isBlank()) {
        builder.header("Authorization", "Bearer " + authToken);
      }

      httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding());
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public List<UserSummary> fetchUsers() throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/users"))
            .timeout(Duration.ofSeconds(5))
            .GET();
    if (authToken != null && !authToken.isBlank()) {
      builder.header("Authorization", "Bearer " + authToken);
    }
    HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() == 200) {
      return parseUsers(resp.body());
    }
    return List.of();
  }

  private static List<UserSummary> parseUsers(String json) {
    List<UserSummary> list = new ArrayList<>();
    if (json == null || json.isBlank()) return list;
    Matcher objMatcher = Pattern.compile("\\{([^}]+)\\}").matcher(json);
    while (objMatcher.find()) {
      String objStr = objMatcher.group(1);
      Long id = null;
      Matcher idM = ID_PATTERN.matcher(objStr);
      if (idM.find()) id = Long.parseLong(idM.group(1));

      String uName = "";
      Matcher uM = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"").matcher(objStr);
      if (uM.find()) uName = uM.group(1);

      String sip = "";
      Matcher sipM = Pattern.compile("\"sipId\"\\s*:\\s*\"([^\"]+)\"").matcher(objStr);
      if (sipM.find()) sip = sipM.group(1);

      String av = null;
      Matcher avM = AVATAR_URL_PATTERN.matcher(objStr);
      if (avM.find()) av = avM.group(1);

      if (!uName.isEmpty() || !sip.isEmpty()) {
        list.add(new UserSummary(id, uName, sip, av));
      }
    }
    return list;
  }

  private static String normalizeBaseUrl(String url) {
    if (url == null || url.trim().isEmpty()) {
      return "http://localhost:8080";
    }
    String s = url.trim();
    if (!s.startsWith("http://") && !s.startsWith("https://")) {
      s = "http://" + s;
    }
    if (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  /**
   * Performs backend authentication via POST /api/login.
   */
  public String login(String username, String password) throws IOException, InterruptedException {
    String json = "{\"username\":\"" + escapeJson(username) + "\",\"password\":\"" + escapeJson(password) + "\"}";
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/login"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() == 200) {
      Matcher m = TOKEN_PATTERN.matcher(resp.body());
      if (m.find()) {
        String token = m.group(1);
        this.authToken = token;
        Matcher am = AVATAR_URL_PATTERN.matcher(resp.body());
        if (am.find()) {
          this.userAvatarUrl = am.group(1);
        } else {
          this.userAvatarUrl = null;
        }
        return token;
      }
      return "";
    } else if (resp.statusCode() == 401) {
      throw new IOException("后台鉴权失败 (HTTP 401 unauthorized)");
    } else {
      throw new IOException("登录后台失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
  }

  /**
   * Performs backend session logout via POST /api/logout, actively revoking active session token.
   */
  public boolean logout() {
    String token = this.authToken;
    this.authToken = null;
    this.userAvatarUrl = null;
    if (token == null || token.isBlank()) {
      return true;
    }
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/logout"))
              .header("Authorization", "Bearer " + token)
              .timeout(Duration.ofSeconds(3))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return resp.statusCode() == 200;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * Probes the backend health endpoint (GET /api/health).
   */
  public boolean isHealthy() {
    return probeHealth(this.baseUrl);
  }

  /**
   * Probes whether the candidate base URL hosts a valid SIP backend (status: ok / UP).
   */
  public static boolean probeHealth(String candidateBaseUrl) {
    if (candidateBaseUrl == null || candidateBaseUrl.isBlank()) {
      return false;
    }
    try {
      String url = normalizeBaseUrl(candidateBaseUrl);
      HttpClient probeClient =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(800))
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build();
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "/api/health"))
              .timeout(Duration.ofMillis(800))
              .GET()
              .build();
      HttpResponse<String> resp = probeClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return resp.statusCode() == 200
          && resp.body() != null
          && resp.body().contains("\"status\"")
          && resp.body().contains("\"esl\"");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Discovers an active backend URL. Checks currentUrl first, then falls back to known local ports
   * (8080, 8088, 8081, 8082, 8083). Returns the reachable URL or null if none found.
   */
  public static String discoverBackendUrl(String currentUrl) {
    if (probeHealth(currentUrl)) {
      return normalizeBaseUrl(currentUrl);
    }
    String host = "localhost";
    try {
      if (currentUrl != null && currentUrl.startsWith("http")) {
        URI u = URI.create(currentUrl);
        if (u.getHost() != null && !u.getHost().isBlank()) {
          host = u.getHost();
        }
      }
    } catch (Exception ignored) {
    }
    int[] candidatePorts = {8080, 8088, 8081, 8082, 8083};
    for (int port : candidatePorts) {
      String candidate = "http://" + host + ":" + port;
      if (!candidate.equalsIgnoreCase(currentUrl) && probeHealth(candidate)) {
        return candidate;
      }
    }
    if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
      for (int port : candidatePorts) {
        String candidate = "http://localhost:" + port;
        if (!candidate.equalsIgnoreCase(currentUrl) && probeHealth(candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }

  /**
   * Uploads a local file to the backend via POST /api/files.
   *
   * @param localFile      the file on disk
   * @param fromUser       sender SIP username
   * @param toUserOrRoom   receiver SIP username or room URI
   * @return FileMessage metadata containing file name, size, and absolute download URL
   */
  public FileMessage uploadFile(Path localFile, String fromUser, String toUserOrRoom)
      throws IOException, InterruptedException {
    Objects.requireNonNull(localFile, "localFile");
    if (!Files.isRegularFile(localFile)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + localFile);
    }

    String fileName = localFile.getFileName().toString();
    long fileSize = Files.size(localFile);
    String contentType = Files.probeContentType(localFile);
    if (contentType == null || contentType.isBlank()) {
      contentType = "application/octet-stream";
    }

    String boundary = "----SipClientBoundary" + System.currentTimeMillis() + Long.toHexString(System.nanoTime());
    byte[] body = buildMultipartBody(boundary, localFile, fileName, contentType, fromUser, toUserOrRoom);

    URI uploadUri = URI.create(baseUrl + "/api/files");
    HttpRequest.Builder uploadReqBuilder =
        HttpRequest.newBuilder()
            .uri(uploadUri)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (authToken != null && !authToken.isBlank()) {
      uploadReqBuilder.header("Authorization", "Bearer " + authToken);
    }
    HttpRequest req = uploadReqBuilder.build();

    HttpResponse<String> resp;
    try {
      resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IOException(
          "连接后台文件服务失败 (" + uploadUri + ")，请确认 Spring Boot 后台已启动: " + e.getMessage(), e);
    }

    int code = resp.statusCode();
    if (code < 200 || code >= 300) {
      throw new IOException("文件上传失败，后台返回 HTTP " + code + ": " + resp.body());
    }

    String respBody = resp.body() == null ? "" : resp.body().trim();
    String downloadUrl = resolveDownloadUrl(respBody);
    if (downloadUrl == null || downloadUrl.isBlank()) {
      throw new IOException("无法从后台返回的响应解析下载地址: " + respBody);
    }

    // Extract size and name from response if present, otherwise use local values
    Matcher mSize = SIZE_PATTERN.matcher(respBody);
    long finalSize = mSize.find() ? Long.parseLong(mSize.group(1)) : fileSize;

    Matcher mName = NAME_PATTERN.matcher(respBody);
    String finalName = mName.find() ? mName.group(1) : fileName;

    return new FileMessage(finalName, finalSize, downloadUrl);
  }

  /**
   * Normalizes a file download URL so that remote hostnames or relative paths
   * are rewritten to this client's current effective baseUrl.
   */
  public String normalizeFileUrl(String url) {
    if (url == null || url.isBlank()) {
      return "";
    }
    String s = url.trim();
    int idx = s.indexOf("/api/files/");
    if (idx != -1) {
      return baseUrl + s.substring(idx);
    }
    if (s.startsWith("/")) {
      return baseUrl + s;
    }
    if (s.matches("^\\d+$")) {
      return baseUrl + "/api/files/" + s;
    }
    if (!s.startsWith("http://") && !s.startsWith("https://")) {
      return baseUrl + "/" + s;
    }
    return s;
  }

  /**
   * Downloads a file from the given URL and saves it to targetFile.
   */
  public Path downloadFile(String downloadUrl, Path targetFile)
      throws IOException, InterruptedException {
    Objects.requireNonNull(downloadUrl, "downloadUrl");
    Objects.requireNonNull(targetFile, "targetFile");

    String absUrl = normalizeFileUrl(downloadUrl);

    HttpRequest.Builder downloadReqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(absUrl))
            .timeout(Duration.ofSeconds(60))
            .GET();
    if (authToken != null && !authToken.isBlank()) {
      downloadReqBuilder.header("Authorization", "Bearer " + authToken);
    }
    HttpRequest req = downloadReqBuilder.build();

    if (targetFile.getParent() != null) {
      Files.createDirectories(targetFile.getParent());
    }

    HttpResponse<Path> resp;
    try {
      resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(
          targetFile,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
          java.nio.file.StandardOpenOption.WRITE));
    } catch (IOException e) {
      throw new IOException("下载文件连接失败 (" + absUrl + "): " + e.getMessage(), e);
    }

    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
      try {
        Files.deleteIfExists(targetFile);
      } catch (Exception ignored) {
      }
      throw new IOException("文件下载失败，HTTP " + resp.statusCode() + " (" + absUrl + ")");
    }

    return targetFile;

  }

  private String resolveDownloadUrl(String responseJson) {
    if (responseJson == null || responseJson.isEmpty()) {
      return null;
    }

    Matcher mUrl = URL_PATTERN.matcher(responseJson);
    if (mUrl.find()) {
      String rawUrl = mUrl.group(1).trim();
      if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
        return rawUrl;
      }
      if (rawUrl.startsWith("/")) {
        return baseUrl + rawUrl;
      }
      return baseUrl + "/" + rawUrl;
    }

    Matcher mId = ID_PATTERN.matcher(responseJson);
    if (mId.find()) {
      return baseUrl + "/api/files/" + mId.group(1);
    }

    return null;
  }

  private static byte[] buildMultipartBody(
      String boundary,
      Path file,
      String fileName,
      String contentType,
      String fromUser,
      String toUser)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String crlf = "\r\n";

    // Field: from
    if (fromUser != null && !fromUser.isBlank()) {
      out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
      out.write(("Content-Disposition: form-data; name=\"from\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
      out.write((fromUser.trim() + crlf).getBytes(StandardCharsets.UTF_8));
    }

    // Field: to
    if (toUser != null && !toUser.isBlank()) {
      out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
      out.write(("Content-Disposition: form-data; name=\"to\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
      out.write((toUser.trim() + crlf).getBytes(StandardCharsets.UTF_8));
    }

    // Field: file
    out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
    out.write(
        ("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"" + crlf)
            .getBytes(StandardCharsets.UTF_8));
    out.write(("Content-Type: " + contentType + crlf + crlf).getBytes(StandardCharsets.UTF_8));
    Files.copy(file, out);
    out.write(crlf.getBytes(StandardCharsets.UTF_8));

    // End boundary
    out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  /**
   * Reports call detail record (CDR) to backend via POST /api/cdr with optional SIP Call-ID.
   */
  public CdrRecord reportCdr(String callId, String caller, String calleeOrRoom, Instant startedAt, long durationSec)
      throws IOException, InterruptedException {
    String startIso = (startedAt != null ? startedAt : Instant.now()).toString();
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"caller\":\"").append(escapeJson(caller)).append("\",");
    sb.append("\"callee_or_room\":\"").append(escapeJson(calleeOrRoom)).append("\",");
    if (callId != null && !callId.isBlank()) {
      sb.append("\"call_id\":\"").append(escapeJson(callId.trim())).append("\",");
    }
    sb.append("\"started_at\":\"").append(startIso).append("\",");
    sb.append("\"duration_sec\":").append(durationSec).append("}");
    String json = sb.toString();

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/cdr"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    if (authToken != null && !authToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + authToken);
    }

    HttpResponse<String> resp =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
      throw new IOException("CDR 上报失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
    return parseCdrRecord(resp.body());
  }

  /**
   * Reports call detail record (CDR) to backend via POST /api/cdr.
   */
  public CdrRecord reportCdr(String caller, String calleeOrRoom, Instant startedAt, long durationSec)
      throws IOException, InterruptedException {
    return reportCdr(null, caller, calleeOrRoom, startedAt, durationSec);
  }

  /**
   * Fetches CDR history list via GET /api/cdr.
   */
  public List<CdrRecord> fetchCdrList(String callerFilter, int limit)
      throws IOException, InterruptedException {
    StringBuilder url = new StringBuilder(baseUrl).append("/api/cdr?limit=").append(limit > 0 ? limit : 100);
    if (callerFilter != null && !callerFilter.isBlank()) {
      url.append("&caller=").append(URLEncoder.encode(callerFilter.trim(), StandardCharsets.UTF_8));
    }

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(url.toString()))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (authToken != null && !authToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + authToken);
    }

    HttpResponse<String> resp =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() != 200) {
      throw new IOException("获取通话历史失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
    return parseCdrList(resp.body());
  }

  /**
   * Fetches overall call statistics via GET /api/cdr/stats.
   */
  public CdrStats fetchStats() throws IOException, InterruptedException {
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/cdr/stats"))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (authToken != null && !authToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + authToken);
    }

    HttpResponse<String> resp =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() != 200) {
      throw new IOException("获取通话统计失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
    return parseCdrStats(resp.body());
  }

  public static CdrRecord parseCdrRecord(String json) {
    if (json == null || json.isEmpty()) return null;
    Matcher mId = ID_PATTERN.matcher(json);
    Long id = mId.find() ? Long.parseLong(mId.group(1)) : null;

    Matcher mCaller = CALLER_PATTERN.matcher(json);
    String caller = mCaller.find() ? mCaller.group(1) : "";

    Matcher mCallee = CALLEE_PATTERN.matcher(json);
    String callee = mCallee.find() ? mCallee.group(1) : "";

    Matcher mTime = STARTED_AT_PATTERN.matcher(json);
    Instant startedAt = null;
    if (mTime.find()) {
      try {
        startedAt = Instant.parse(mTime.group(1));
      } catch (Exception ignored) {}
    }
    if (startedAt == null) startedAt = Instant.now();

    Matcher mDur = DURATION_PATTERN.matcher(json);
    long dur = mDur.find() ? Long.parseLong(mDur.group(1)) : 0;

    return new CdrRecord(id, caller, callee, startedAt, dur);
  }

  public static List<CdrRecord> parseCdrList(String json) {
    List<CdrRecord> list = new ArrayList<>();
    if (json == null || json.isEmpty()) return list;
    Matcher m = Pattern.compile("\\{[^{}]+\\}").matcher(json);
    while (m.find()) {
      CdrRecord rec = parseCdrRecord(m.group());
      if (rec != null && !rec.caller().isEmpty()) {
        list.add(rec);
      }
    }
    return list;
  }

  public static CdrStats parseCdrStats(String json) {
    if (json == null || json.isEmpty()) return new CdrStats(0, 0, 0.0, 0);
    Matcher mCalls = TOTAL_CALLS_PATTERN.matcher(json);
    long calls = mCalls.find() ? Long.parseLong(mCalls.group(1)) : 0;

    Matcher mDur = TOTAL_DURATION_PATTERN.matcher(json);
    long dur = mDur.find() ? Long.parseLong(mDur.group(1)) : 0;

    Matcher mAvg = AVG_DURATION_PATTERN.matcher(json);
    double avg = mAvg.find() ? Double.parseDouble(mAvg.group(1)) : 0.0;

    Matcher mPeak = PEAK_MEMBERS_PATTERN.matcher(json);
    int peak = mPeak.find() ? Integer.parseInt(mPeak.group(1)) : 0;

    return new CdrStats(calls, dur, avg, peak);
  }

  /**
   * Fetches room list from backend via GET /api/rooms.
   */
  public List<RoomInfo> fetchRooms() throws IOException, InterruptedException {
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/rooms"))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (authToken != null && !authToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + authToken);
    }
    HttpResponse<String> resp =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() != 200) {
      throw new IOException("获取房间列表失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
    return parseRoomList(resp.body());
  }

  /**
   * Creates a new conference room in backend via POST /api/rooms.
   * Backend returns the standardized room-<id> URI.
   */
  public RoomInfo createRoom(String name, Integer maxMembers) throws IOException, InterruptedException {
    int max = (maxMembers != null && maxMembers > 0) ? maxMembers : 8;
    String json = "{\"name\":\"" + escapeJson(name) + "\",\"max_members\":" + max + "}";
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/rooms"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    if (authToken != null && !authToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + authToken);
    }
    HttpResponse<String> resp =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() != 200 && resp.statusCode() != 201) {
      throw new IOException("创建房间失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
    return parseRoomInfo(resp.body());
  }

  public static RoomInfo parseRoomInfo(String json) {
    if (json == null || json.isEmpty()) return null;
    Matcher mId = ROOM_ID_PATTERN.matcher(json);
    Long id = mId.find() ? Long.parseLong(mId.group(1)) : null;

    Matcher mName = ROOM_NAME_PATTERN.matcher(json);
    String name = mName.find() ? mName.group(1) : "";

    Matcher mUri = ROOM_SIP_URI_PATTERN.matcher(json);
    String uri = mUri.find() ? mUri.group(1) : "";

    Matcher mMax = ROOM_MAX_MEMBERS_PATTERN.matcher(json);
    int max = mMax.find() ? Integer.parseInt(mMax.group(1)) : 8;

    Matcher mCount = ROOM_MEMBER_COUNT_PATTERN.matcher(json);
    int count = mCount.find() ? Integer.parseInt(mCount.group(1)) : 0;

    return new RoomInfo(id, name, uri, max, count);
  }

  public static List<RoomInfo> parseRoomList(String json) {
    List<RoomInfo> list = new ArrayList<>();
    if (json == null || json.isEmpty()) return list;
    Matcher m = Pattern.compile("\\{[^{}]+\\}").matcher(json);
    while (m.find()) {
      RoomInfo r = parseRoomInfo(m.group());
      if (r != null && r.sipUri() != null && !r.sipUri().isEmpty()) {
        list.add(r);
      }
    }
    return list;
  }

  /**
   * Sends an instant text message or file notification to a conference room via POST /api/rooms/{identifier}/messages.
   */
  public RoomMessageInfo sendRoomMessage(String roomUri, String sender, String content, boolean isFile)
      throws IOException, InterruptedException {
    String json = "{\"sender\":\"" + escapeJson(sender) + "\",\"content\":\"" + escapeJson(content) + "\",\"isFile\":" + isFile + "}";
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/rooms/" + roomUri + "/messages"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    if (authToken != null && !authToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + authToken);
    }
    HttpResponse<String> resp =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
      throw new IOException("发送房间消息失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
    return parseRoomMessage(resp.body());
  }

  /**
   * Fetches room messages newer than afterId via GET /api/rooms/{identifier}/messages?afterId={afterId}.
   */
  public List<RoomMessageInfo> fetchRoomMessages(String roomUri, long afterId)
      throws IOException, InterruptedException {
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/rooms/" + roomUri + "/messages?afterId=" + afterId))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (authToken != null && !authToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + authToken);
    }
    HttpResponse<String> resp =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() != 200) {
      throw new IOException("获取房间消息失败 (HTTP " + resp.statusCode() + "): " + resp.body());
    }
    return parseRoomMessageList(resp.body());
  }

  public static RoomMessageInfo parseRoomMessage(String json) {
    if (json == null || json.isEmpty()) return null;
    Matcher mId = ID_PATTERN.matcher(json);
    long id = mId.find() ? Long.parseLong(mId.group(1)) : 0;

    String roomUri = extractJsonString(json, "roomUri");
    if (roomUri == null) roomUri = extractJsonString(json, "room_uri");
    if (roomUri == null) roomUri = "";

    String sender = extractJsonString(json, "sender");
    if (sender == null) sender = "";

    String content = extractJsonString(json, "content");
    if (content == null) content = "";

    Matcher mIsFile = Pattern.compile("\"(?:isFile|is_file)\"\\s*:\\s*(true|false)").matcher(json);
    boolean isFile = mIsFile.find() && "true".equalsIgnoreCase(mIsFile.group(1));

    String tsStr = extractJsonString(json, "timestamp");
    Instant ts = null;
    if (tsStr != null && !tsStr.isBlank()) {
      try {
        ts = Instant.parse(tsStr);
      } catch (Exception ignored) {}
    }
    if (ts == null) ts = Instant.now();

    return new RoomMessageInfo(id, roomUri, sender, content, isFile, ts);
  }

  public static List<RoomMessageInfo> parseRoomMessageList(String json) {
    List<RoomMessageInfo> list = new ArrayList<>();
    if (json == null || json.isEmpty()) return list;
    Matcher m = Pattern.compile("\\{[^{}]+\\}").matcher(json);
    while (m.find()) {
      RoomMessageInfo info = parseRoomMessage(m.group());
      if (info != null && info.id() > 0) {
        list.add(info);
      }
    }
    return list;
  }

  private static String extractJsonString(String json, String field) {
    Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"");
    Matcher m = p.matcher(json);
    if (m.find()) {
      return unescapeJson(m.group(1));
    }
    return null;
  }

  private static String unescapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
  }
}


