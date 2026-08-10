# LoveChatFilter — notes for Claude Code

## Planned feature (not yet implemented): auto-moderation with consequences

Requested by the server owner (2026-08-10). Spans **LoveChatFilter** and **LoveBehaivor**
(mirrored note in `LoveBehaivor/CLAUDE.md`) — read both before implementing.

### Idea

`/rep` in LoveBehaivor is currently a manual peer-rating command (players praise/complain about
each other). The owner wants an automatic link instead: profanity/spam/caps caught by
LoveChatFilter should directly dock a player's politeness score (no manual report needed), and
detecting courteous phrases ("спасибо", "пожалуйста", etc.) should give a small bonus. LoveChatFilter
stops being just censorship and becomes a signal source for LoveBehaivor's reputation system.

### What to build in LoveChatFilter

1. **Fire an event on violation.** `FilterEngine.processChat` (`src/main/java/me/lovelace/lovechatfilter/filters/FilterEngine.java`)
   is where profanity/spam/caps get caught today, but there's no event system yet —
   `LoveChatFilterAPI` (`api/LoveChatFilterAPI.java`) only exposes `isProfane(String)`. Add a
   Bukkit event (e.g. `ChatViolationEvent`) carrying the violating player, violation type
   (profanity/spam/caps/ads — whichever `FilterEngine`'s internal modules already distinguish),
   and a severity, fired whenever `processChat` cancels or edits a message. `FilterEngine.ProcessResult`
   and the `ContentType` enum (CHAT/SIGN/BOOK/ITEM) are the existing detection surface to hook into.
2. **Detect polite phrases.** Add a configurable phrase list ("спасибо", "пожалуйста", etc.) and
   fire a lightweight signal when one appears in chat — a separate low-stakes event (or the same
   event with a POLITE category). This is a pure scan, not a filter action — it must never block
   or alter the message.
3. **Improve filter detection quality as much as possible** — this was explicitly requested
   ("улучши фильтрацию максимально, чтоб срабатывала"). Harden `AdvancedProfanityFilter` /
   `GrammarManager` against evasion: leetspeak, extra spacing/punctuation between letters,
   repeated-letter stretching, Cyrillic/Latin homoglyph substitution, transliteration. Check what
   `GrammarManager` already normalizes before adding new passes, to avoid duplicating logic.
4. Neither plugin currently soft-depends on the other (`plugin.yml` softdepend lists checked
   2026-08-10) — add `LoveBehaivor` to LoveChatFilter's (or the reverse) so the event has someone
   listening, or keep it decoupled and let LoveBehaivor discover LoveChatFilter's API via
   `ServicesManager` like the `isProfane` check already does elsewhere in the ecosystem.

### What to build in LoveBehaivor

- Listen for LoveChatFilter's new event(s) and call `BehaviorManager.addPolitenessPoints(uuid, delta)`
  (`src/main/java/me/lovelace/lovebehavior/managers/BehaviorManager.java:127`) — negative delta on
  violation, small positive delta on a detected polite phrase.
- Needs config for delta size per violation type/severity and per polite-phrase hit.
- Needs a cooldown/dedup so spamming "спасибо" doesn't farm reputation, and probably a cap on how
  much auto-adjustment can move politeness within a time window (compare to how `recordPraise`/
  `recordComplain` already rate-limit manual `/rep` actions).
- Decide whether these automatic adjustments should show up in `getRepHistory` (`BehaviorManager.java:423`)
  the same way `recordAdminAdjustment` entries do, so players can see why their score moved.

### Open questions for whoever picks this up

- Exact point deltas per violation severity / polite phrase.
- Whether auto-penalties need admin visibility/logging beyond rep history.
- Whether the polite-phrase list should be per-server configurable or ships with sane Russian/English defaults.
