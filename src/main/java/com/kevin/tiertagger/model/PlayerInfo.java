package com.kevin.tiertagger.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.kevin.tiertagger.TierCache;
import com.kevin.tiertagger.TierTagger;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public record PlayerInfo(String uuid, String name, Map<String, Ranking> rankings, String region, int points,
                         int overall, List<Badge> badges, @SerializedName("combat_master") boolean combatMaster) {
    public record Ranking(int tier, int pos, @Nullable @SerializedName("peak_tier") Integer peakTier,
                          @Nullable @SerializedName("peak_pos") Integer peakPos, long attained,
                          boolean retired) {

        /**
         * Lower is better.
         */
        public int comparableTier() {
            return tier * 2 + pos;
        }

        /**
         * Lower is better.
         */
        public int comparablePeak() {
            if (peakTier == null || peakPos == null) {
                return Integer.MAX_VALUE;
            } else {
                return peakTier * 2 + peakPos;
            }
        }

        public NamedRanking asNamed(GameMode mode) {
            return new NamedRanking(mode, this);
        }
    }

    public record NamedRanking(@Nullable GameMode mode, Ranking ranking) {
    }

    public record Badge(String title, String desc) {
    }

    private static final Map<String, Integer> REGION_COLORS = Map.of(
            "NA", 0xff6a6e,
            "EU", 0x6aff6e,
            "SA", 0xff9900,
            "AU", 0xf6b26b,
            "ME", 0xffd966,
            "AS", 0xc27ba0,
            "AF", 0x674ea7
    );

    public static CompletableFuture<PlayerInfo> get(HttpClient client, UUID uuid) {
        String endpoint = TierTagger.getManager().getConfig().getApiUrl() + "/v2/profile/" + uuid;
        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(s -> TierTagger.GSON.fromJson(s, PlayerInfo.class))
                .whenComplete((i, t) -> {
                    if (t != null) TierTagger.getLogger().warn("Error getting player info ({})", uuid, t);
                });
    }

    public static CompletableFuture<Map<String, Ranking>> getRankings(HttpClient client, UUID uuid) {
        String apiUrl = TierTagger.getManager().getConfig().getApiUrl();
        boolean mctiersAsia = TierList.isMctiersAsia(apiUrl);
        String endpoint = mctiersAsia
                ? apiUrl + "/players/" + uuid + "/tiers"
                : apiUrl + "/v2/profile/" + uuid + "/rankings";
        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(s -> {
                    if (mctiersAsia) {
                        return parseMctiersAsiaRankings(TierTagger.GSON.fromJson(s, JsonObject.class).getAsJsonArray("tiers"));
                    }
                    return TierTagger.GSON.fromJson(s, new TypeToken<Map<String, Ranking>>() {});
                })
                .whenComplete((i, t) -> {
                    if (t != null) TierTagger.getLogger().warn("Error getting player rankings ({})", uuid, t);
                });
    }

    public static CompletableFuture<PlayerInfo> search(HttpClient client, String query) {
        String apiUrl = TierTagger.getManager().getConfig().getApiUrl();
        if (TierList.isMctiersAsia(apiUrl)) {
            String endpoint = apiUrl + "/players/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> TierTagger.GSON.fromJson(response.body(), JsonObject.class))
                    .thenCompose(search -> {
                        JsonArray results = search.getAsJsonArray("results");
                        if (results.isEmpty()) {
                            return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
                        }
                        JsonObject result = results.get(0).getAsJsonObject();
                        String uuid = result.get("uuid").getAsString();
                        String username = result.get("username").getAsString();
                        HttpRequest profileRequest = HttpRequest.newBuilder(URI.create(apiUrl + "/players/" + uuid)).GET().build();
                        return client.sendAsync(profileRequest, HttpResponse.BodyHandlers.ofString())
                                .thenApply(response -> parseMctiersAsiaProfile(
                                        TierTagger.GSON.fromJson(response.body(), JsonObject.class), username));
                    })
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) TierTagger.getLogger().warn("Error searching player {}", query, throwable);
                    });
        }

        String endpoint = apiUrl + "/v2/profile/by-name/" + query;
        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(s -> TierTagger.GSON.fromJson(s, PlayerInfo.class))
                .whenComplete((i, t) -> {
                    if (t != null) TierTagger.getLogger().warn("Error searching player {}", query, t);
                });
    }

    private static PlayerInfo parseMctiersAsiaProfile(JsonObject profile, String username) {
        String region = profile.has("region") && !profile.get("region").isJsonNull()
                ? profile.get("region").getAsString() : "";
        return new PlayerInfo(
                profile.get("minecraft_uuid").getAsString(),
                username,
                parseMctiersAsiaRankings(profile.getAsJsonArray("tiers")),
                region,
                numberOrZero(profile, "totalPoints"),
                numberOrZero(profile, "globalRank"),
                List.of(),
                false
        );
    }

    private static Map<String, Ranking> parseMctiersAsiaRankings(JsonArray tiers) {
        Map<String, Ranking> rankings = new LinkedHashMap<>();
        for (JsonElement element : tiers) {
            JsonObject tier = element.getAsJsonObject();
            String mode = tier.get("gamemode").getAsString();
            String label = tier.get("tier").getAsString().toUpperCase(Locale.ROOT);
            if (label.length() != 3 || label.charAt(1) != 'T' || (label.charAt(0) != 'H' && label.charAt(0) != 'L')) {
                continue;
            }
            int tierNumber = label.charAt(2) - '0';
            if (tierNumber < 1 || tierNumber > 5) {
                continue;
            }
            int position = label.charAt(0) == 'H' ? 0 : 1;
            boolean retired = tier.has("retired_at") && !tier.get("retired_at").isJsonNull();
            Integer peakTier = retired ? tierNumber : null;
            Integer peakPosition = retired ? position : null;
            rankings.put(mode, new Ranking(tierNumber, position, peakTier, peakPosition, 0, retired));
        }
        return rankings;
    }

    private static int numberOrZero(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return 0;
        try {
            return value.getAsInt();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int getRegionColor() {
        return REGION_COLORS.getOrDefault(this.region.toUpperCase(Locale.ROOT), 0xffffff);
    }

    public static Optional<NamedRanking> getHighestRanking(Map<String, Ranking> rankings) {
        return rankings.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .min(Comparator.comparingInt(e -> e.getValue().comparableTier()))
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())));
    }

    @Getter
    @AllArgsConstructor
    public enum PointInfo {
        COMBAT_GRANDMASTER("Combat Grandmaster", 0xE6C622, 0xFDE047),
        COMBAT_MASTER("Combat Master", 0xFBB03B, 0xFFD13A),
        COMBAT_ACE("Combat Ace", 0xCD285C, 0xD65474),
        COMBAT_SPECIALIST("Combat Specialist", 0xAD78D8, 0xC7A3E8),
        COMBAT_CADET("Combat Cadet", 0x9291D9, 0xADACE2),
        COMBAT_NOVICE("Combat Novice", 0x9291D9, 0xFFFFFF),
        ROOKIE("Rookie", 0x6C7178, 0x8B979C),
        UNRANKED("Unranked", 0xFFFFFF, 0xFFFFFF);

        private final String title;
        private final int color;
        private final int accentColor;
    }

    public PointInfo getPointInfo() {
        if (this.points >= 400) {
            return PointInfo.COMBAT_GRANDMASTER;
        } else if (this.points >= 250) {
            return PointInfo.COMBAT_MASTER;
        } else if (this.points >= 100) {
            return PointInfo.COMBAT_ACE;
        } else if (this.points >= 50) {
            return PointInfo.COMBAT_SPECIALIST;
        } else if (this.points >= 20) {
            return PointInfo.COMBAT_CADET;
        } else if (this.points >= 10) {
            return PointInfo.COMBAT_NOVICE;
        } else if (this.points >= 1) {
            return PointInfo.ROOKIE;
        } else {
            return PointInfo.UNRANKED;
        }
    }

    public List<NamedRanking> getSortedTiers() {
        List<NamedRanking> tiers = new ArrayList<>(this.rankings.entrySet().stream()
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())))
                .toList());

        tiers.sort(Comparator.comparing((NamedRanking a) -> a.ranking.retired, Boolean::compare)
                .thenComparingInt(a -> a.ranking.tier)
                .thenComparingInt(a -> a.ranking.pos));

        return tiers;
    }
}
