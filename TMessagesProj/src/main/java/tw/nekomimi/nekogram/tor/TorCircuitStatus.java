package tw.nekomimi.nekogram.tor;

/** Stream-ready circuits, including Tor's linked Conflux legs. */
public final class TorCircuitStatus {
    private TorCircuitStatus() {}

    public static boolean hasUsableCircuit(String status) {
        if (status == null) return false;
        for (String line : status.split("\\r?\\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 4 || !"BUILT".equals(fields[1]) || fields[2].split(",").length < 3) continue;
            boolean usablePurpose = false;
            boolean internal = false;
            for (int i = 3; i < fields.length; i++) {
                if ("PURPOSE=GENERAL".equals(fields[i]) || "PURPOSE=CONFLUX_LINKED".equals(fields[i])) usablePurpose = true;
                if (fields[i].startsWith("BUILD_FLAGS=")) {
                    for (String flag : fields[i].substring(12).split(",")) {
                        if ("IS_INTERNAL".equals(flag) || "ONEHOP_TUNNEL".equals(flag)) internal = true;
                    }
                }
            }
            if (usablePurpose && !internal) return true;
        }
        return false;
    }
}
