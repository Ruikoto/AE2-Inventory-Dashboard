package local.ae2dashboard.assets;

import java.util.List;

public record AssetManifest(
    int formatVersion,
    String identityContract,
    String minecraftVersion,
    String forgeVersion,
    String ae2Version,
    String language,
    String generatedAt,
    List<String> mods,
    List<String> resourcePacks,
    int skipped,
    List<Entry> entries,
    List<Page> pages) {
  public static final String CONTRACT = "ae2wi-1.2.0-pre-forge-1.20.1";

  public record Entry(
      String id, String registryId, String type, String name, String page, int x, int y) {}

  public record Page(String hash, int width, int height) {}
}
