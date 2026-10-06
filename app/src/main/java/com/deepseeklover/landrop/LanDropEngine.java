package com.deepseeklover.landrop;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** The desktop landrop-v1 protocol. No Android dependencies, so interoperability is tested on the JVM. */
public final class LanDropEngine implements Closeable {
    public static final long MAX_FILE = 20L * 1024 * 1024 * 1024;
    private static final Set<String> TERMINAL = new HashSet<>(Arrays.asList("completed", "failed", "rejected", "cancelled"));
    public interface Assets { InputStream open(String name) throws IOException; }
    public interface Source { InputStream open() throws IOException; }
    public interface Storage {
        Sink create(String name, String id) throws Exception;
        String label();
    }
    public interface Sink {
        OutputStream open() throws Exception;
        String commit() throws Exception;
        void abort();
    }
    public static final class DiskStorage implements Storage {
        private final File directory;
        public DiskStorage(File directory) throws IOException {
            this.directory = directory;
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建接收文件夹");
        }
        public String label() { return directory.getAbsolutePath(); }
        public synchronized Sink create(String name, String id) throws Exception {
            String clean = safeName(name);
            int dot = clean.lastIndexOf('.');
            String stem = dot > 0 ? clean.substring(0, dot) : clean, ext = dot > 0 ? clean.substring(dot) : "";
            File reserved = null;
            for (int i = 0; i < 10000; i++) {
                File candidate = new File(directory, stem + (i == 0 ? "" : " (" + i + ")") + ext);
                if (candidate.createNewFile()) { reserved = candidate; break; }
            }
            if (reserved == null) throw new IOException("同名文件过多");
            final File destination = reserved, partial = new File(directory, "." + id + ".part");
            if (!partial.createNewFile()) { destination.delete(); throw new IOException("无法创建临时文件"); }
            return new Sink() {
                public OutputStream open() throws IOException { return new FileOutputStream(partial); }
                public String commit() throws IOException {
                    Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return destination.getAbsolutePath();
                }
                public void abort() { partial.delete(); destination.delete(); }
            };
        }
    }
    private static final class Job {
        final JSONObject data;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile Socket socket;
        volatile InputStream input;
        String senderAddress, remoteId;
        JSONObject remotePeer;
        Sink sink;
        Job(JSONObject data) { this.data = data; }
    }
    private final File stateFile;
    private final Assets assets;
    private final LinkedHashMap<String, JSONObject> peers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final ThreadPoolExecutor inbound = new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final ExecutorService outbound = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    private final String token = randomToken();
    private volatile Storage storage;
    private volatile boolean running, enabled = true;
    private String id = UUID.randomUUID().toString(), name, language = "zh-CN", warning = "";
    private ServerSocket server;
    private int port;
    public LanDropEngine(File stateFile, Storage storage, Assets assets, String defaultName) throws Exception {
        this.stateFile = stateFile; this.storage = storage; this.assets = assets; name = defaultName;
        if (stateFile.isFile()) {
            // Never reset a broken state file: identity and user history must not silently disappear.
            JSONObject saved = new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
            id = saved.getString("id"); name = saved.optString("name", defaultName);
            language = saved.optString("language", "zh-CN"); enabled = saved.optBoolean("enabled", true);
            JSONArray configured = saved.optJSONArray("peers");
            if (configured != null) for (int i = 0; i < configured.length(); i++) {
                JSONObject p = configured.getJSONObject(i);
                if (!privateAddress(p.optString("address")) || p.optInt("port") < 1 || p.optInt("port") > 65535) continue;
                p.put("online", false); p.remove("token"); peers.put(p.getString("id"), p);
            }
            JSONArray history = saved.optJSONArray("history");
            if (history != null) for (int i = 0; i < history.length(); i++) {
                JSONObject data = history.getJSONObject(i);
                if (!terminal(data)) { data.put("status", "failed"); data.put("error", "应用已停止，传输未完成"); }
                jobs.put(data.getString("id"), new Job(data));
            }
        }
    }
    public synchronized void start(int requestedPort) throws Exception {
        if (running) return;
        server = new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress("0.0.0.0", requestedPort), 32);
        port = server.getLocalPort(); running = true; save();
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = server.accept(); socket.setSoTimeout(60000); sockets.add(socket);
                    try { inbound.execute(() -> handle(socket)); }
                    catch (RejectedExecutionException busy) { sockets.remove(socket); socket.close(); }
                } catch (IOException ignored) { if (!running) break; }
            }
        }, "LanDrop-listener"); accept.setDaemon(true); accept.start();
        scheduler.scheduleWithFixedDelay(() -> {
            if (!running) return;
            try { scan(); expire(); } catch (Exception ignored) { }
        }, 1, 5, TimeUnit.SECONDS);
    }
    public int port() { return port; }
    public synchronized String language() { return language; }
    public synchronized boolean isEnabled() { return enabled; }
    public synchronized boolean hasActiveTransfers() { return jobs.values().stream().anyMatch(j -> !terminal(j.data)); }
    public synchronized JSONObject info() throws Exception {
        return new JSONObject().put("protocol", "landrop-v1").put("id", id).put("name", name).put("platform", "android").put("port", port).put("token", token).put("enabled", enabled);
    }
    public synchronized JSONObject state() throws Exception {
        JSONArray addresses = new JSONArray(), interfaces = new JSONArray();
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        if (networks != null) while (networks.hasMoreElements()) {
            NetworkInterface network = networks.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            Enumeration<InetAddress> ips = network.getInetAddresses();
            while (ips.hasMoreElements()) {
                InetAddress ip = ips.nextElement(); String address = ip.getHostAddress();
                if (!(ip instanceof Inet4Address) || !privateAddress(address)) continue;
                addresses.put(address);
                interfaces.put(new JSONObject().put("name", network.getName().startsWith("wlan") ? "Wi-Fi" : network.getName()).put("address", address).put("virtual", network.getName().startsWith("tun")));
            }
        }
        JSONArray configured = new JSONArray();
        for (JSONObject p : peers.values()) { JSONObject copy = cloneJson(p); copy.remove("token"); configured.put(copy); }
        JSONArray history = new JSONArray(); for (Job j : jobs.values()) history.put(cloneJson(j.data));
        return new JSONObject().put("self", new JSONObject().put("id", id).put("name", name).put("platform", "android").put("port", port).put("addresses", addresses).put("interfaces", interfaces))
                .put("settings", new JSONObject().put("receiveDir", storage.label()).put("enabled", enabled).put("language", language))
                .put("peers", configured).put("transfers", history).put("warning", warning).put("uiToken", "native");
    }
    public synchronized void setWarning(String text) { warning = text; }
    public synchronized void setStorage(Storage next) throws Exception {
        if (hasActiveTransfers()) throw new IOException("请先完成或取消传输，再更改接收文件夹");
        storage = next;
    }
    public synchronized JSONObject completedJob(String jobId) throws Exception {
        Job job = jobs.get(jobId);
        if (job == null || !job.data.optString("status").equals("completed") || !job.data.has("path")) throw new IOException("接收文件已被移动或删除");
        return cloneJson(job.data);
    }
    public JSONObject addPeer(String endpoint) throws Exception {
        String[] parts = endpoint.trim().split(":", -1);
        if (parts.length > 2 || !privateAddress(parts[0])) throw new IOException("请输入局域网 IPv4 地址，例如 192.168.1.8:45878");
        int peerPort;
        try { peerPort = parts.length == 2 ? Integer.parseInt(parts[1]) : 45878; } catch (NumberFormatException e) { throw new IOException("连接端口无效"); }
        if (peerPort < 1 || peerPort > 65535) throw new IOException("连接端口无效");
        JSONObject candidate = new JSONObject().put("address", parts[0]).put("port", peerPort);
        JSONObject remote = request(candidate, "/peer/info", "GET", null, null);
        validateInfo(remote);
        synchronized (this) {
            if (remote.getString("id").equals(id)) throw new IOException("这是当前设备，请添加另一台设备");
            remote.put("address", parts[0]).put("online", true).put("lastSeen", System.currentTimeMillis());
            Iterator<JSONObject> iterator = peers.values().iterator();
            while (iterator.hasNext()) { JSONObject old = iterator.next(); if (old.optString("address").equals(parts[0]) && old.optInt("port") == peerPort) iterator.remove(); }
            peers.put(remote.getString("id"), remote); save();
        }
        return new JSONObject().put("id", remote.getString("id")).put("ok", true);
    }
    public void scan() throws Exception {
        List<JSONObject> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(); for (JSONObject p : peers.values()) snapshot.add(cloneJson(p)); }
        for (JSONObject p : snapshot) {
            if (!running) return;
            try { refresh(p, null); }
            catch (Exception e) { synchronized (this) { JSONObject current = peers.get(p.optString("id")); if (current != null) current.put("online", false).put("error", friendlyError(e)); } }
        }
    }
    private JSONObject refresh(JSONObject peer, Job job) throws Exception {
        JSONObject remote = request(peer, "/peer/info", "GET", null, job); validateInfo(remote);
        if (!remote.getString("id").equals(peer.getString("id"))) throw new IOException("设备身份已改变，请重新添加设备");
        synchronized (this) {
            JSONObject current = peers.get(peer.getString("id"));
            if (current == null) throw new IOException("设备已被移除");
            current.put("token", remote.getString("token")).put("name", remote.getString("name")).put("platform", remote.optString("platform"))
                    .put("enabled", remote.optBoolean("enabled", true)).put("online", true).put("error", "").put("lastSeen", System.currentTimeMillis());
            return cloneJson(current);
        }
    }
    public synchronized JSONObject command(String route, String method, JSONObject data) throws Exception {
        if (route.equals("/api/settings")) {
            if (data.has("language") && !Arrays.asList("zh-CN", "en").contains(data.getString("language"))) throw new IOException("不支持的界面语言");
            if (data.has("language")) language = data.getString("language");
            if (data.has("name")) { String next = data.getString("name").trim(); if (!next.isEmpty()) name = next.substring(0, Math.min(40, next.length())); }
            if (data.has("enabled")) { enabled = data.getBoolean("enabled"); if (!enabled) for (Job j : new ArrayList<>(jobs.values())) cancel(j.data.getString("id")); }
        } else if (route.equals("/api/cancel")) cancel(data.getString("id"));
        else if (route.startsWith("/api/peers/") && method.equals("DELETE")) {
            String peerId = route.substring("/api/peers/".length()); peers.remove(peerId);
            for (Job j : new ArrayList<>(jobs.values())) if (j.data.optString("peerId").equals(peerId)) cancel(j.data.getString("id"));
        } else if (route.equals("/api/history") && method.equals("DELETE")) jobs.values().removeIf(j -> terminal(j.data));
        else if (route.startsWith("/api/history/") && method.equals("DELETE")) {
            String jobId = route.substring("/api/history/".length()); Job job = jobs.get(jobId);
            if (job != null && !terminal(job.data)) throw new IOException("传输尚未结束，请先取消传输"); jobs.remove(jobId);
        } else throw new IOException("接口不存在");
        save(); return new JSONObject().put("ok", true);
    }
    public synchronized String sendText(String peerId, String text) throws Exception {
        if (text == null || text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 100000) throw new IOException("文字不能为空，且不得超过 100 KB");
        return enqueue(peerId, "text", "文字消息", text.getBytes(StandardCharsets.UTF_8).length, text, null);
    }
    public synchronized String sendFile(String peerId, String filename, long size, Source source) throws Exception {
        if (size < 0 || size > MAX_FILE) throw new IOException("单个文件上限为 20 GB");
        return enqueue(peerId, "file", safeName(filename), size, null, source);
    }
    private String enqueue(String peerId, String kind, String filename, long size, String text, Source source) throws Exception {
        if (!running || !enabled) throw new IOException("请在偏好设置中开启局域网传输");
        JSONObject p = peers.get(peerId); if (p == null) throw new IOException("请先选择在线设备");
        if (activeCount() >= 50) throw new IOException("传输队列已满");
        Job job = newJob("outgoing", kind, filename, size, text, p, "connecting");
        JSONObject peer = cloneJson(p); outbound.execute(() -> send(job, peer, source));
        return job.data.getString("id");
    }
    private void send(Job job, JSONObject peer, Source source) {
        try {
            check(job); peer = refresh(peer, job); job.remotePeer = peer;
            if (!peer.optBoolean("enabled", true)) throw new IOException("对方已关闭局域网传输");
            JSONObject meta = new JSONObject().put("sender", info()).put("kind", job.data.getString("kind")).put("name", job.data.getString("name")).put("size", job.data.getLong("size"));
            if (job.data.has("text")) meta.put("text", job.data.getString("text"));
            JSONObject prepared = request(peer, "/peer/request", "POST", meta, job);
            job.remoteId = prepared.getString("id"); check(job);
            if (job.data.getString("kind").equals("text")) { complete(job, null, null); return; }
            update(job, "transferring", null);
            HttpURLConnection connection = connection(peer, "/peer/upload/" + job.remoteId, "POST", job);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setFixedLengthStreamingMode(job.data.getLong("size")); connection.setDoOutput(true);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count;
            try (InputStream input = source.open(); OutputStream output = connection.getOutputStream()) {
                job.input = input; count = copy(job, input, output, job.data.getLong("size"), digest);
                if (input.read() != -1) throw new IOException("文件大小已改变，请重新选择文件");
            }
            JSONObject response = readResponse(connection); release(connection, job);
            if (count != job.data.getLong("size") || !hex(digest.digest()).equals(response.optString("sha256"))) throw new IOException("文件完整性校验失败");
            complete(job, null, response.getString("sha256"));
        } catch (Exception e) {
            try { update(job, job.cancelled ? "cancelled" : "failed", job.cancelled ? "传输已取消" : friendlyError(e)); } catch (Exception ignored) { }
            if (job.remoteId != null && running) try { request(peer, "/peer/request/" + job.remoteId, "DELETE", null, null); } catch (Exception ignored) { }
        } finally {
            if (job.connection != null) release(job.connection, job);
            if (job.input != null) try { job.input.close(); } catch (Exception ignored) { }
        }
    }
    private long copy(Job job, InputStream input, OutputStream output, long size, MessageDigest digest) throws Exception {
        byte[] buffer = new byte[65536]; long count = 0, lastBytes = 0, last = System.currentTimeMillis();
        while (count < size) {
            check(job); int n = input.read(buffer, 0, (int)Math.min(buffer.length, size - count));
            if (n < 0) throw new EOFException("传输中断，未保存不完整文件");
            output.write(buffer, 0, n); digest.update(buffer, 0, n); count += n;
            long now = System.currentTimeMillis();
            if (now - last >= 200 || count == size) {
                synchronized (this) { job.data.put("bytes", count).put("progress", size == 0 ? 0 : Math.min(99, count * 100 / size)).put("speed", (count - lastBytes) * 1000 / Math.max(1, now - last)); }
                last = now; lastBytes = count;
            }
        }
        check(job); output.flush(); return count;
    }
    public synchronized void cancel(String jobId) throws Exception {
        Job job = jobs.get(jobId); if (job == null || terminal(job.data)) return;
        job.cancelled = true; update(job, "cancelled", "传输已取消");
        if (job.socket != null) try { job.socket.close(); } catch (IOException ignored) { }
        if (job.connection != null) job.connection.disconnect();
        if (job.input != null) try { job.input.close(); } catch (IOException ignored) { }
    }
    private synchronized Job newJob(String direction, String kind, String filename, long size, String text, JSONObject peer, String status) throws Exception {
        JSONObject data = new JSONObject().put("id", UUID.randomUUID().toString()).put("direction", direction).put("kind", kind).put("name", filename).put("size", size)
                .put("peerId", peer.getString("id")).put("peerName", peer.getString("name")).put("status", status).put("bytes", 0).put("progress", 0).put("createdAt", System.currentTimeMillis());
        if (text != null) data.put("text", text);
        Job job = new Job(data); jobs.put(data.getString("id"), job); trim(); save(); return job;
    }
    private synchronized void update(Job job, String status, String error) throws Exception {
        if (terminal(job.data)) return;
        job.data.put("status", status); if (error != null) job.data.put("error", error);
        if (TERMINAL.contains(status)) { job.data.put("finishedAt", System.currentTimeMillis()); save(); }
    }
    private synchronized void complete(Job job, String path, String hash) throws Exception {
        check(job); if (terminal(job.data)) throw new IOException("传输已结束");
        job.data.put("status", "completed").put("progress", 100).put("bytes", job.data.getLong("size")).put("finishedAt", System.currentTimeMillis());
        if (path != null) job.data.put("path", path); if (hash != null) job.data.put("sha256", hash); save();
    }
    private synchronized int activeCount() { int count = 0; for (Job j : jobs.values()) if (!terminal(j.data)) count++; return count; }
    private synchronized void expire() throws Exception {
        for (Job j : jobs.values()) if (j.data.optString("status").equals("ready") && System.currentTimeMillis() - j.data.optLong("createdAt") > 60000) update(j, "failed", "发送方未开始传输");
    }
    private void handle(Socket socket) {
        try (Socket owned = socket) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String first = line(input, 4096); String[] requestLine = first.split(" ");
            if (requestLine.length != 3) throw new IOException("无效请求");
            String method = requestLine[0], route = requestLine[1]; Map<String, String> headers = new HashMap<>(); int headerSize = first.length();
            while (true) { String h = line(input, 4096); headerSize += h.length(); if (headerSize > 16384) throw new IOException("请求头过大"); if (h.isEmpty()) break; int separator = h.indexOf(':'); if (separator < 1) throw new IOException("无效请求头"); String key = h.substring(0, separator).toLowerCase(Locale.ROOT); if (headers.put(key, h.substring(separator + 1).trim()) != null) throw new IOException("重复请求头"); }
            if (headers.containsKey("transfer-encoding")) { reply(socket, 400, error("不支持分块请求")); return; }
            String remote = socket.getInetAddress().getHostAddress();
            if (!privateAddress(remote)) { reply(socket, 403, error("只允许局域网设备连接")); return; }
            if (route.startsWith("/peer/")) {
                if (headers.containsKey("origin")) { reply(socket, 403, error("请求来源不受信任")); return; }
                if (route.equals("/peer/info") && method.equals("GET")) { reply(socket, 200, info()); return; }
                if (!token.equals(headers.get("x-lan-token"))) { reply(socket, 403, error("设备凭证已更新，请重新连接")); return; }
                if (route.equals("/peer/request") && method.equals("POST")) {
                    if (!enabled) { reply(socket, 403, error("对方已关闭局域网传输")); return; }
                    JSONObject data = new JSONObject(new String(readBody(input, headers, 160000), StandardCharsets.UTF_8));
                    JSONObject sender = data.optJSONObject("sender"); String kind = data.optString("kind"); long size = data.optLong("size", -1);
                    if (!Arrays.asList("file", "text").contains(kind) || size < 0 || size > MAX_FILE || sender == null || !(sender.opt("name") instanceof String)) { reply(socket, 400, error("无效的传输请求")); return; }
                    if (kind.equals("text") && (!(data.opt("text") instanceof String) || data.getString("text").trim().isEmpty() || size > 100000 || data.getString("text").getBytes(StandardCharsets.UTF_8).length != size)) { reply(socket, 400, error("无效的文字消息")); return; }
                    Job job;
                    synchronized (this) {
                        JSONObject peer = peers.get(sender.optString("id"));
                        if (peer == null || !peer.optString("address").equals(remote) || peer.optInt("port") != sender.optInt("port")) { reply(socket, 403, error("请在两台软件里互相添加 IP 和端口")); return; }
                        if (activeCount() >= 50) { reply(socket, 429, error("接收队列已满")); return; }
                        job = newJob("incoming", kind, kind.equals("text") ? "文字消息" : safeName(data.optString("name")), size, kind.equals("text") ? data.getString("text") : null, peer, "ready");
                        job.senderAddress = remote;
                        if (kind.equals("text")) complete(job, null, null);
                    }
                    reply(socket, 200, new JSONObject().put("id", job.data.getString("id"))); return;
                }
                if (route.startsWith("/peer/request/") && method.equals("DELETE")) {
                    synchronized (this) {
                        Job job = jobs.get(route.substring("/peer/request/".length()));
                        if (job == null || !remote.equals(job.senderAddress) || !job.data.optString("direction").equals("incoming")) { reply(socket, 403, error("无权取消此传输")); return; }
                        cancel(job.data.getString("id"));
                    }
                    reply(socket, 200, new JSONObject().put("ok", true)); return;
                }
                if (route.startsWith("/peer/upload/") && method.equals("POST")) {
                    Job job;
                    synchronized (this) {
                        job = jobs.get(route.substring("/peer/upload/".length()));
                        if (job == null || !job.data.optString("status").equals("ready")) { reply(socket, 409, error("接收请求已过期")); return; }
                        if (!remote.equals(job.senderAddress)) { reply(socket, 403, error("上传设备不一致")); return; }
                        if (!enabled || Long.parseLong(headers.getOrDefault("content-length", "-1")) != job.data.getLong("size")) { cancel(job.data.getString("id")); reply(socket, 400, error("文件大小不匹配或传输已关闭")); return; }
                        job.socket = socket; update(job, "transferring", null);
                    }
                    receive(job, input, socket); return;
                }
                reply(socket, 404, error("接口不存在")); return;
            }
            if (!socket.getInetAddress().isLoopbackAddress() || !("127.0.0.1:" + port).equals(headers.get("host"))) { reply(socket, 403, error("请在本机打开应用")); return; }
            Map<String, String> allowed = new HashMap<>();
            for (String file : Arrays.asList("index.html", "app.js", "i18n.js", "community-links.js", "community.js", "styles.css", "mobile.css", "native.js", "logo.png", "mascot-received.png", "mascot-sent.png", "mark.svg")) allowed.put("/" + file, file);
            String file = route.equals("/") ? "index.html" : allowed.get(route);
            if (!method.equals("GET") || file == null || assets == null) { reply(socket, 404, error("页面不存在")); return; }
            try (InputStream resource = assets.open(file)) {
                byte[] bytes = boundedRead(resource, 8 * 1024 * 1024);
                String mime = file.endsWith(".js") ? "application/javascript" : file.endsWith(".css") ? "text/css" : file.endsWith(".png") ? "image/png" : file.endsWith(".svg") ? "image/svg+xml" : "text/html";
                writeResponse(socket, 200, mime, bytes, true);
            }
        } catch (Exception e) { try { reply(socket, 400, error(friendlyError(e))); } catch (Exception ignored) { } }
        finally { sockets.remove(socket); }
    }
    private void receive(Job job, InputStream input, Socket socket) throws Exception {
        Sink sink = null; boolean committed = false;
        try {
            synchronized (this) { check(job); sink = storage.create(job.data.getString("name"), job.data.getString("id")); job.sink = sink; }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream output = sink.open()) { copy(job, input, output, job.data.getLong("size"), digest); }
            String hash = hex(digest.digest());
            synchronized (this) { check(job); String destination = sink.commit(); complete(job, destination, hash); committed = true; }
            reply(socket, 200, new JSONObject().put("ok", true).put("sha256", hash));
        } catch (Exception e) {
            update(job, job.cancelled ? "cancelled" : "failed", job.cancelled ? "传输已取消" : friendlyError(e));
            try { reply(socket, 500, error(friendlyError(e))); } catch (Exception ignored) { }
        } finally { if (sink != null && !committed) sink.abort(); job.socket = null; job.sink = null; }
    }
    private HttpURLConnection connection(JSONObject peer, String route, String method, Job job) throws Exception {
        if (job != null) check(job);
        URL url = new URL("http", peer.getString("address"), peer.getInt("port"), route);
        HttpURLConnection connection = (HttpURLConnection)url.openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(30000); connection.setReadTimeout(60000); connection.setRequestMethod(method); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Connection", "close");
        if (peer.has("token")) connection.setRequestProperty("X-Lan-Token", peer.getString("token"));
        connections.add(connection); if (job != null) job.connection = connection;
        if (!running) { release(connection, job); throw new IOException("软件已停止"); }
        return connection;
    }
    private JSONObject request(JSONObject peer, String route, String method, JSONObject body, Job job) throws Exception {
        HttpURLConnection connection = connection(peer, route, method, job);
        try {
            connection.setReadTimeout(30000);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            return readResponse(connection);
        } finally { release(connection, job); }
    }
    private JSONObject readResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) throw new IOException("对方服务没有返回数据");
        JSONObject response;
        try (InputStream input = stream) { response = new JSONObject(new String(boundedRead(input, 160000), StandardCharsets.UTF_8)); }
        if (status < 200 || status >= 300) throw new IOException(response.optString("error", "对方拒绝了传输"));
        return response;
    }
    private void release(HttpURLConnection connection, Job job) { connections.remove(connection); connection.disconnect(); if (job != null && job.connection == connection) job.connection = null; }
    private static void validateInfo(JSONObject remote) throws Exception {
        if (!remote.optString("protocol").equals("landrop-v1") || !remote.optString("token").matches("[a-f0-9]{64}") || !(remote.opt("id") instanceof String) || !(remote.opt("name") instanceof String)) throw new IOException("该地址没有运行局域传送");
    }
    private void check(Job job) throws IOException { if (!running || job.cancelled) throw new IOException("传输已取消"); }
    private static boolean terminal(JSONObject data) { return TERMINAL.contains(data.optString("status")); }
    private void trim() {
        if (jobs.size() <= 200) return;
        Iterator<Job> iterator = jobs.values().iterator(); while (jobs.size() > 200 && iterator.hasNext()) if (terminal(iterator.next().data)) iterator.remove();
    }
    private synchronized void save() throws Exception {
        JSONArray configured = new JSONArray(), history = new JSONArray();
        for (JSONObject peer : peers.values()) configured.put(new JSONObject().put("id", peer.getString("id")).put("name", peer.getString("name")).put("address", peer.getString("address")).put("port", peer.getInt("port")).put("platform", peer.optString("platform")));
        for (Job job : jobs.values()) history.put(cloneJson(job.data));
        JSONObject saved = new JSONObject().put("id", id).put("name", name).put("language", language).put("enabled", enabled).put("peers", configured).put("history", history);
        File directory = stateFile.getParentFile(); if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存配置");
        File temporary = new File(directory, stateFile.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(saved.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    public void close() throws IOException {
        synchronized (this) {
            if (!running) return; running = false;
            for (Job job : jobs.values()) if (!terminal(job.data)) try { cancel(job.data.getString("id")); } catch (Exception ignored) { }
        }
        scheduler.shutdownNow(); outbound.shutdownNow(); inbound.shutdownNow();
        if (server != null) server.close();
        for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) { }
        for (HttpURLConnection connection : connections) connection.disconnect();
        try { outbound.awaitTermination(3, TimeUnit.SECONDS); inbound.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (this) { try { save(); } catch (Exception e) { throw new IOException(e); } }
    }
    private static String line(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int previous = -1;
        while (bytes.size() <= limit) { int value = input.read(); if (value == -1) throw new EOFException("连接已中断"); if (previous == '\r' && value == '\n') { byte[] data = bytes.toByteArray(); return new String(data, 0, data.length - 1, StandardCharsets.US_ASCII); } bytes.write(value); previous = value; }
        throw new IOException("请求头过大");
    }
    private static byte[] readBody(InputStream input, Map<String, String> headers, int limit) throws Exception {
        long size = Long.parseLong(headers.getOrDefault("content-length", "0")); if (size < 0 || size > limit) throw new IOException("请求内容过大");
        byte[] body = new byte[(int)size]; int total = 0;
        while (total < body.length) { int read = input.read(body, total, body.length - total); if (read < 0) throw new EOFException("请求未完成"); total += read; } return body;
    }
    private static byte[] boundedRead(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) { if (output.size() + n > limit) throw new IOException("响应过大"); output.write(buffer, 0, n); } return output.toByteArray();
    }
    private static JSONObject error(String message) throws Exception { return new JSONObject().put("error", message); }
    private static void reply(Socket socket, int status, JSONObject body) throws IOException { writeResponse(socket, status, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), false); }
    private static void writeResponse(Socket socket, int status, String mime, byte[] bytes, boolean page) throws IOException {
        String security = page ? "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n" : "";
        byte[] header = ("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + mime + "; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" + security + "\r\n").getBytes(StandardCharsets.US_ASCII);
        OutputStream output = socket.getOutputStream(); output.write(header); output.write(bytes); output.flush();
    }
    private static JSONObject cloneJson(JSONObject original) throws Exception { return new JSONObject(original.toString()); }
    public static boolean privateAddress(String address) {
        if (address == null || !address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] parts = address.split("\\."); int[] n = new int[4];
        for (int i = 0; i < 4; i++) { n[i] = Integer.parseInt(parts[i]); if (n[i] > 255) return false; }
        return n[0] == 10 || n[0] == 127 || n[0] == 192 && n[1] == 168 || n[0] == 172 && n[1] >= 16 && n[1] <= 31 || n[0] == 169 && n[1] == 254;
    }
    public static String safeName(String filename) {
        if (filename == null) return "file";
        String clean = filename.replaceAll("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]", "_").trim().replaceAll("[. ]+$", "");
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "file";
        return clean.substring(0, Math.min(180, clean.length()));
    }
    private static String randomToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return hex(bytes); }
    private static String hex(byte[] bytes) { StringBuilder output = new StringBuilder(); for (byte b : bytes) output.append(String.format(Locale.ROOT, "%02x", b & 255)); return output.toString(); }
    public static String friendlyError(Exception error) {
        if (error instanceof SocketTimeoutException) return "连接或传输超时，请检查地址、网络和防火墙";
        if (error instanceof ConnectException) return "对方端口拒绝连接，请打开对方软件并核对地址";
        if (error instanceof NoRouteToHostException) return "无法到达对方网络，请检查 Wi-Fi 和设备隔离设置";
        if (error instanceof org.json.JSONException) return "对方返回的数据无效，请确认端口运行的是 LanDrop";
        return error.getMessage() == null ? "操作失败" : error.getMessage();
    }
}
