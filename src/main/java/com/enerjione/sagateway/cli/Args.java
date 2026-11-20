package com.enerjione.sagateway.cli;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** {@code --ad deger} ve {@code --bayrak} bicimli basit arguman okuyucu. */
public final class Args {

    private final Map<String, String> values = new HashMap<>();
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
            values.put(key, argv[++i]);
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

    public boolean flag(String key) {
        return flags.contains(key);
    }
}
