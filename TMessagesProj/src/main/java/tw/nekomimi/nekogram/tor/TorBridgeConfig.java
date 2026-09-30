package tw.nekomimi.nekogram.tor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.util.HashMap;

/** Pure config builder. Never accept torrc directives pasted into the bridge field. */
public final class TorBridgeConfig {
    private TorBridgeConfig() {}
    private static final Set<String> TRANSPORTS = Set.of("webtunnel");

    public static String transport(String mode) {
        if (TRANSPORTS.contains(mode)) return mode;
        throw new IllegalArgumentException("Unsupported Tor bridge transport: " + mode);
    }

    public static List<String> lines(String mode, String bridges) {
        if (!"custom".equals(mode) && !"direct".equals(mode)) transport(mode);
        if (bridges == null || bridges.trim().isEmpty()) throw new IllegalArgumentException("Bridge lines are required");
        List<String> lines = new ArrayList<>();
        for (String raw : bridges.split("\\r?\\n")) {
            if (raw.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
                throw new IllegalArgumentException("Invalid control character in bridge line");
            }
            String bridge = raw.trim();
            if (bridge.isEmpty() || bridge.startsWith("#")) continue;
            if (bridge.matches("^Bridge\\s+.*")) bridge = bridge.substring(6).trim();
            if (bridge.isEmpty() || bridge.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127) || bridge.length() > 4096) {
                throw new IllegalArgumentException("Invalid bridge line");
            }
            String type = bridge.split("\\s+", 2)[0];
            if ("custom".equals(mode)) {
                if (TRANSPORTS.contains(type)) transport(type);
            } else if (!type.equals(transport(mode))) {
                throw new IllegalArgumentException("Bridge transport does not match selected mode");
            }
            validate(bridge);
            bridge = String.join(" ", bridge.split("\\s+"));
            if (!lines.contains(bridge)) lines.add(bridge);
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("Bridge lines are required");
        return lines;
    }

    private static void validate(String line) {
        String[] parts = line.split("\\s+");
        boolean transported = TRANSPORTS.contains(parts[0]);
        int index = transported ? 1 : 0;
        if (index >= parts.length) throw new IllegalArgumentException("Bridge address is required");
        String address = parts[index++];
        try {
            URI endpoint = new URI("tcp://" + address);
            String host = endpoint.getHost();
            if (host == null || endpoint.getPort() < 1 || endpoint.getPort() > 65535 ||
                    endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null ||
                    endpoint.getRawFragment() != null || !endpoint.getRawPath().isEmpty()) throw new Exception();
            if (!host.startsWith("[")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4) throw new Exception();
                for (String octet : octets) {
                    if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) throw new Exception();
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid bridge IP address or port");
        }
        if (index < parts.length && !parts[index].contains("=")) {
            if (!parts[index++].matches("[a-fA-F0-9]{40}")) throw new IllegalArgumentException("Invalid bridge fingerprint");
        }
        Map<String, String> args = new HashMap<>();
        for (; index < parts.length; index++) {
            int equals = parts[index].indexOf('=');
            if (equals <= 0 || equals == parts[index].length() - 1) throw new IllegalArgumentException("Invalid bridge argument");
            String key = parts[index].substring(0, equals);
            if (args.put(key, parts[index].substring(equals + 1)) != null) throw new IllegalArgumentException("Duplicate bridge argument: " + key);
        }
        if ("webtunnel".equals(parts[0]) && !args.containsKey("url")) throw new IllegalArgumentException("WebTunnel URL is required");
        if ("webtunnel".equals(parts[0]) && args.containsKey("cert")) {
            try {
                if (!args.get("cert").matches("[A-Za-z0-9+/]{43}=") || java.util.Base64.getDecoder().decode(args.get("cert")).length != 32) throw new Exception();
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid WebTunnel certificate hash");
            }
        }
        for (String key : new String[]{"url", "ampcache"}) {
            if (!args.containsKey(key)) continue;
            try {
                URI uri = new URI(args.get(key));
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null ||
                        uri.getRawUserInfo() != null || uri.getRawFragment() != null ||
                        uri.getPort() == 0 || uri.getPort() > 65535) throw new Exception();
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid HTTP(S) bridge " + key);
            }
        }

    }

    public static List<String> transports(String mode, String bridges) {
        if ("direct".equals(mode)) return Collections.emptyList();
        List<String> lines = lines(mode, bridges);
        if (!"custom".equals(mode)) return Collections.singletonList(transport(mode));
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String line : lines) {
            String type = line.split("\\s+", 2)[0];
            if (TRANSPORTS.contains(type)) names.add(type);
        }
        return new ArrayList<>(names);
    }

    public static List<String> build(String mode, String bridges, long port) {
        if ("direct".equals(mode)) return Collections.singletonList("UseBridges 0");
        String type = "custom".equals(mode) ? null : transport(mode);
        return build(mode, bridges, type == null ? Collections.emptyMap() : Collections.singletonMap(type, port));
    }

    public static List<String> build(String mode, String bridges, Map<String, Long> ports) {
        if ("direct".equals(mode)) return Collections.singletonList("UseBridges 0");
        List<String> bridgesList = lines(mode, bridges);
        List<String> config = new ArrayList<>();
        config.add("UseBridges 1");
        for (String name : transports(mode, bridges)) {
            long port = ports.getOrDefault(name, 0L);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Transport is not listening: " + name);
            config.add("ClientTransportPlugin " + name + " socks5 127.0.0.1:" + port);
        }
        for (String bridge : bridgesList) config.add("Bridge " + bridge);
        return config;
    }
}
