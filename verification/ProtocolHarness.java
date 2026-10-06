import com.deepseeklover.landrop.LanDropEngine;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ProtocolHarness {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        File root = new File(args[0]); root.mkdirs();
        LanDropEngine engine = new LanDropEngine(new File(root, "state.json"), new LanDropEngine.DiskStorage(new File(root, "received")), null, "Android test peer");
        engine.start(args.length > 1 ? Integer.parseInt(args[1]) : 0);
        System.out.println(engine.info());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject request = new JSONObject(line), response = new JSONObject().put("requestId", request.getString("requestId"));
                try {
                    switch (request.getString("action")) {
                        case "state": response.put("result", engine.state()); break;
                        case "peer": response.put("result", engine.addPeer(request.getString("address"))); break;
                        case "text": response.put("result", engine.sendText(request.getString("peerId"), request.getString("text"))); break;
                        case "file": {
                            File file = new File(request.getString("path"));
                            response.put("result", engine.sendFile(request.getString("peerId"), request.optString("name", file.getName()), file.length(), () -> new FileInputStream(file))); break;
                        }
                        case "command": response.put("result", engine.command(request.getString("route"), request.optString("method", "POST"), request.optJSONObject("body"))); break;
                        case "close": engine.close(); response.put("result", true); System.out.println(response); return;
                        default: throw new Exception("Unknown action");
                    }
                } catch (Exception error) { response.put("error", error.toString()); }
                System.out.println(response);
            }
        } finally { engine.close(); }
    }
}
