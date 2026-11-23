package com.whut.map.map_service.llm.agent.advisory;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects advisories whose text claims an own-ship course over ground (COG) that
 * does not match the trigger snapshot. Only explicit, current-tense claims are
 * compared; target-ship courses, planned courses and relative turns are ignored.
 */
public final class AdvisoryCogValidator {

    public static final double MAX_DEVIATION_DEG = 1.0;

    private static final String NUM = "(?<![\\d.])(-?\\d{1,6}(?:\\.\\d+)?)(?![\\d.])";
    private static final String NUM_WRAPPED = "[（(]?\\s*" + NUM;
    private static final String OWN_MARKER = "(?:本船|我船|本舰|own[_\\s-]?ship(?:_state)?)";
    private static final String CURRENT_MARKER = "(?:当前|目前|现在|此刻|此时|实时|实测|实际)";
    private static final String COG_TERM = "(?:对地真航向|对地航向|航迹向|COG|cog_deg|cog|航向)";
    private static final String CONTEXT_FILLER = "[\\s.。:：的（）()中]*";
    private static final String TERM_SUFFIX =
            "\\s*(?:[（(]\\s*(?:COG|cog_deg)\\s*[）)])?\\s*"
                    + "(?:约为|约|为|是|＝|=|:|：|达到)?\\s*";

    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[。；;，,、\\n]|而|但是|但|同时");
    private static final Pattern OWN_MARKER_PATTERN = Pattern.compile(OWN_MARKER, Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_MARKER_PATTERN = Pattern.compile(
            "目标船|目标|来船|他船|会遇船|target(?:\\s+ship)?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PLANNED_MARKER_PATTERN = Pattern.compile(
            "建议|拟|计划|打算|准备|希望|意图|目标|预计|预期|应当|应该|调整|改为|改至|改到|改成|转为|转到|转至|转向"
    );
    private static final Pattern COG_MENTION_PATTERN = Pattern.compile(
            "对地真航向|对地航向|航迹向|COG|cog_deg|cog|航向",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CURRENT_MARKER_PATTERN = Pattern.compile(CURRENT_MARKER);

    private static final Pattern FROM_TO_CLAIM = Pattern.compile(
            "(?:" + COG_TERM + ")?\\s*从\\s*(?:当前\\s*)?(?:" + COG_TERM + "\\s*)?(?:当前\\s*)?(?:为|是|＝|=|:|：)?\\s*"
                    + NUM_WRAPPED + "\\s*(?:度|°)?\\s*"
                    + "(?:改为|改至|改到|改成|转为|转到|转至|转向至|转向到|调整至|调整到|变为|变至|至|到)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OWN_TERM_CLAIM = Pattern.compile(
            OWN_MARKER + CONTEXT_FILLER + "(?:" + CURRENT_MARKER + ")?" + CONTEXT_FILLER
                    + COG_TERM + TERM_SUFFIX + NUM_WRAPPED,
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CURRENT_TERM_CLAIM = Pattern.compile(
            CURRENT_MARKER + CONTEXT_FILLER + COG_TERM + TERM_SUFFIX + NUM_WRAPPED,
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TERM_CURRENT_CLAIM = Pattern.compile(
            COG_TERM + "\\s*" + CURRENT_MARKER + TERM_SUFFIX + NUM_WRAPPED,
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BARE_TERM_CLAIM = Pattern.compile(
            COG_TERM + TERM_SUFFIX + NUM_WRAPPED,
            Pattern.CASE_INSENSITIVE
    );

    private static final List<Pattern> CLAIM_PATTERNS = List.of(
            FROM_TO_CLAIM,
            OWN_TERM_CLAIM,
            CURRENT_TERM_CLAIM,
            TERM_CURRENT_CLAIM,
            BARE_TERM_CLAIM
    );

    private enum Context {
        NONE, OWN, TARGET
    }

    private AdvisoryCogValidator() {
    }

    public static Result validate(AgentSnapshot snapshot, AdvisoryOutputParser.ParsedAdvisory parsed) {
        List<Claim> claims = new ArrayList<>();
        collectClaims(parsed.recommendedAction() != null ? parsed.recommendedAction().getDescription() : null,
                "recommended_action.description", true, claims);
        collectClaims(parsed.summary(), "summary", false, claims);
        if (parsed.evidenceItems() != null) {
            for (int i = 0; i < parsed.evidenceItems().size(); i++) {
                collectClaims(parsed.evidenceItems().get(i), "evidence_items[" + i + "]", false, claims);
            }
        }

        if (claims.isEmpty()) {
            return Result.ok();
        }

        Double referenceCog = referenceCog(snapshot);
        if (referenceCog == null) {
            Claim first = claims.get(0);
            return Result.rejected(first.field(), first.value(), null,
                    "own-ship COG claim present but snapshot reference COG is missing or invalid");
        }

        for (Claim claim : claims) {
            Double normalizedClaim = normalizeClaim(claim.value());
            if (normalizedClaim == null) {
                return Result.rejected(claim.field(), claim.value(), referenceCog,
                        "claimed own-ship COG is outside the valid [0, 360] range");
            }
            double deviation = circularDifferenceDeg(normalizedClaim, referenceCog);
            if (deviation > MAX_DEVIATION_DEG) {
                return Result.rejected(claim.field(), claim.value(), referenceCog,
                        "own-ship COG claim does not match snapshot reference");
            }
        }
        return Result.ok();
    }

    public static double circularDifferenceDeg(double first, double second) {
        double diff = Math.abs(first - second) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    private static Double normalizeClaim(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 360.0) {
            return null;
        }
        return value == 360.0 ? 0.0 : value;
    }

    private static Double referenceCog(AgentSnapshot snapshot) {
        if (snapshot == null || snapshot.riskContext() == null) {
            return null;
        }
        LlmRiskOwnShipContext ownShip = snapshot.riskContext().getOwnShip();
        if (ownShip == null) {
            return null;
        }
        double cog = ownShip.getCog();
        if (!Double.isFinite(cog) || cog < 0.0 || cog >= 360.0) {
            return null;
        }
        return cog;
    }

    private static void collectClaims(String text, String field, boolean implicitOwnShip, List<Claim> claims) {
        if (text == null || text.isBlank()) {
            return;
        }
        Context inherited = Context.NONE;
        for (String segment : SEGMENT_SEPARATOR.split(text)) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            for (Pattern pattern : CLAIM_PATTERNS) {
                Matcher matcher = pattern.matcher(segment);
                while (matcher.find()) {
                    if (pattern == FROM_TO_CLAIM && !COG_MENTION_PATTERN.matcher(matcher.group()).find()) {
                        continue;
                    }
                    if (pattern != FROM_TO_CLAIM
                            && !CURRENT_MARKER_PATTERN.matcher(matcher.group()).find()
                            && isPlannedContext(segment, matcher.start())) {
                        continue;
                    }
                    Context context = contextAt(segment, matcher.start(), inherited);
                    if (context == Context.TARGET) {
                        continue;
                    }
                    if (context == Context.NONE && !implicitOwnShip) {
                        continue;
                    }
                    double value;
                    try {
                        value = Double.parseDouble(matcher.group(1));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    Claim claim = new Claim(field, value);
                    if (!claims.contains(claim)) {
                        claims.add(claim);
                    }
                }
            }
            inherited = contextAfterSegment(segment, inherited);
        }
    }

    private static boolean isPlannedContext(String segment, int matchStart) {
        int from = Math.max(0, matchStart - 12);
        return PLANNED_MARKER_PATTERN.matcher(segment.substring(from, matchStart)).find();
    }

    private static Context contextAt(String segment, int matchStart, Context inherited) {
        int ownMarker = lastMarkerAtOrBefore(OWN_MARKER_PATTERN, segment, matchStart);
        int targetMarker = lastMarkerAtOrBefore(TARGET_MARKER_PATTERN, segment, matchStart);
        if (ownMarker < 0 && targetMarker < 0) {
            return inherited;
        }
        return ownMarker >= targetMarker ? Context.OWN : Context.TARGET;
    }

    private static Context contextAfterSegment(String segment, Context inherited) {
        int ownMarker = lastMarker(OWN_MARKER_PATTERN, segment);
        int targetMarker = lastMarker(TARGET_MARKER_PATTERN, segment);
        if (ownMarker < 0 && targetMarker < 0) {
            return inherited;
        }
        return ownMarker >= targetMarker ? Context.OWN : Context.TARGET;
    }

    private static int lastMarkerAtOrBefore(Pattern marker, String text, int matchStart) {
        Matcher matcher = marker.matcher(text);
        int last = -1;
        while (matcher.find() && matcher.start() <= matchStart) {
            last = matcher.start();
        }
        return last;
    }

    private static int lastMarker(Pattern marker, String text) {
        Matcher matcher = marker.matcher(text);
        int last = -1;
        while (matcher.find()) {
            last = matcher.start();
        }
        return last;
    }

    private record Claim(String field, double value) {
    }

    public record Result(
            boolean accepted,
            String field,
            Double claimedCog,
            Double referenceCog,
            String reason
    ) {
        private static Result ok() {
            return new Result(true, null, null, null, null);
        }

        private static Result rejected(String field, Double claimedCog, Double referenceCog, String reason) {
            return new Result(false, field, claimedCog, referenceCog, reason);
        }
    }
}