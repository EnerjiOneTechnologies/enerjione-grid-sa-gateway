package com.enerjione.sagateway.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code --ad deger} ve {@code --bayrak} bicimli basit arguman okuyucu. */
public final class Args {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, List<String>> all = new HashMap<>();
    private final Set<String> flags = new HashSet<>();

    public Args(String[] argv, int from, Set<String> knownFlags) {
        for (int i = from; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("beklenmeyen arguman: " + a);
            String key = a.substring(2);
            if (knownFlags.contains(key)) {
                flags.add(key);
                continue;
            }
            if (i + 1 >= argv.length) throw new IllegalArgumentException("deger eksik: " + a);
            String v = argv[++i];
            values.put(key, v);
            all.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
    }

    public String str(String key, String def) {
        return values.getOrDefault(key, def);
    }

    public String required(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("--" + key + " zorunlu");
        return v;
    }

    public int integer(String key, int def) {
        String v = values.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " sayi olmali: " + v);
        }
    }

    /** Tekrarlanabilen secenegin tum degerleri, verilis sirasiyla (yoksa bos). */
    public List<String> list(String key) {
        return List.copyOf(all.getOrDefault(key, List.of()));
    }

    public boolean flag(String key) {
        return flags.contains(key);
    }
}
