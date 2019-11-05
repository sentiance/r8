package com.android.tools.r8.dex;

import com.android.tools.r8.graph.DexString;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.io.UTFDataFormatException;
import java.util.Comparator;
import java.util.Map;

public class ClassesChecksum {

  private static final String PREFIX = "~~~";
  private static final char PREFIX_CHAR0 = '~';
  private static final char PREFIX_CHAR1 = '~';
  private static final char PREFIX_CHAR2 = '~';

  private Object2LongMap<String> dictionary = null;

  public ClassesChecksum() {
    assert PREFIX.length() == 3;
    assert PREFIX.charAt(0) == PREFIX_CHAR0;
    assert PREFIX.charAt(1) == PREFIX_CHAR1;
    assert PREFIX.charAt(2) == PREFIX_CHAR2;
  }

  private void ensureMap() {
    if (dictionary == null) {
      dictionary = new Object2LongOpenHashMap<>();
    }
  }

  private void append(JsonObject json) {
    ensureMap();
    json.entrySet()
        .forEach(
            entry ->
                dictionary.put(entry.getKey(), Long.parseLong(entry.getValue().getAsString(), 16)));
  }

  public void addChecksum(String classDescriptor, long crc) {
    ensureMap();
    dictionary.put(classDescriptor, crc);
  }

  public Object2LongMap<String> getChecksums() {
    return dictionary;
  }

  public String toJsonString() {
    // In order to make printing of markers deterministic we sort the entries by key.
    final JsonObject sortedJson = new JsonObject();
    dictionary.object2LongEntrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .forEach(
            entry ->
                sortedJson.addProperty(entry.getKey(), Long.toString(entry.getLongValue(), 16)));
    return "" + PREFIX_CHAR0 + PREFIX_CHAR1 + PREFIX_CHAR2 + sortedJson;
  }

  // Try to parse the string as a marker and append its content if successful.
  public void tryParseAndAppend(DexString dexString) {
    if (dexString.size > 2
        && dexString.content[0] == PREFIX_CHAR0
        && dexString.content[1] == PREFIX_CHAR1
        && dexString.content[2] == PREFIX_CHAR2) {
      String str = dexString.toString().substring(3);
      try {
        JsonElement result = new JsonParser().parse(str);
        if (result.isJsonObject()) {
          append(result.getAsJsonObject());
        }
      } catch (JsonSyntaxException ignored) {}
    }
  }

  /**
   * Check if this string will definitely preceded the checksum marker.
   *
   * <p>If true is returned the string passed definitely preceded the checksum marker. If false is
   * returned the string passed might still preceded, so this can give false negatives.
   *
   * @param string String to check if definitely preceded the checksum marker.
   * @return If the string passed definitely preceded the checksum marker
   */
  public static boolean definitelyPrecedesChecksumMarker(DexString string) {
    try {
      assert PREFIX.length() == 3;
      char[] prefix = new char[PREFIX.length()];
      int prefixLength = string.decodePrefix(prefix);
      return prefixLength == 0
          || (prefixLength == 1 && prefix[0] <= PREFIX_CHAR0)
          || (prefixLength == 2 && prefix[0] == PREFIX_CHAR0 && prefix[1] <= PREFIX_CHAR1)
          || (prefixLength == 3
              && prefix[0] == PREFIX_CHAR0
              && prefix[1] == PREFIX_CHAR1
              && prefix[2] < PREFIX_CHAR2);
    } catch (UTFDataFormatException e) {
      throw new RuntimeException("Bad format", e);
    }
  }
}
