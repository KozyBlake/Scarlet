
# Changelog

## Unreleased
  - Updated `vrchatapi-java` to `1.20.8-nightly.14`
  - Fixed the Settings UI occasionally painting card contents outside the scroll viewport during rapid resize/scroll, which could draw controls over the tab strip, search row, or footer
  - Indefinitely postponed: Support for multiple groups
  - Pending: Staff & Instance Analysis, (live infographic?)
  - Pending: Limited Google Drive interoperability
  - Pending: Distinct Server and Client modes

## 0.4.16-b5
  - Added "lite" edition built alongside the full edition — `mvn package` now produces both `scarlet-0.4.16-b5.jar` and `scarlet-0.4.16-b5-lite.jar`; the lite JAR has no RVC UI, no Python bridge, and skips the torch/rvc-python dependency install flow
  - Added compile-time feature flag `net.sybyline.scarlet.Features.RVC_ENABLED`, driven by the bundled `scarlet-features.properties` resource (stripped by the lite shade execution)
  - Added `tts_rvc_pitch` setting (range -24..+24 semitones) under Settings → Text-to-Speech for tuning the RVC model's pitch offset when the TTS voice's fundamental doesn't match what the model was trained on
  - Added `--models-dir` flag to the RVC Python bridge so it finds models even when the bridge is extracted to a different subtree than the user's models directory
  - Added stem-contains matching for `.index` files so a model and its retrieval index are paired even when the filenames don't match exactly
  - Added automatic refresh of classpath-extracted bridge assets on startup so upgraded Python bridges take effect without requiring users to delete the AppData copy
  - Fixed RVC conversion failing with `_pickle.UnpicklingError: Weights only load failed` on PyTorch 2.6+ by transparently restoring `torch.load`'s `weights_only=False` default and allowlisting `fairseq.data.dictionary.Dictionary` via `torch.serialization.add_safe_globals`
  - Fixed RVC conversion failing with `Expected a JsonObject but was JsonPrimitive` when rvc-python progress prints leaked onto stdout — the bridge now routes library stdout to stderr during inference, and Java parses the last balanced JSON object rather than the whole buffer as a defence-in-depth measure
  - Fixed dependency-install wheel picker falling back to CPU on CUDA 13.0 because of a broken version comparison; now uses tuple comparison and correctly selects `cu124` for CUDA ≥ 12.4
  - Fixed unnecessary torch reinstall causing `WinError 5 — Access is denied` on Windows when the DLLs were already loaded; the install step is now skipped entirely when both `torch` and `torchaudio` are already present
  - Fixed post-install `_try_import` checks reporting packages as missing due to stale `importlib` finder / metadata caches; caches are now invalidated after every pip run
  - Fixed `torchcodec` incorrectly gating `rvc_compatible` in `--status` output; it is now in the optional package set (rvc-python 0.1.5 does not import it)
  - Fixed RVC-related settings appearing in the UI of the lite edition — they are now filtered out when `Features.RVC_ENABLED` is false
  - Fixed `java.io.IOException: The handle is invalid` being thrown ten times a second from `Scarlet.spin()` on Windows when the user launched Scarlet without an attached console (double-clicked JAR via `javaw.exe`, Windows shortcut, Task Scheduler, detached launch) — the CLI reader now detects the invalid stdin handle on first failure, logs it once at INFO, and skips further `System.in.available()` polling for the session
  - Fixed `ErrorResponseException: -1: java.io.InterruptedIOException` being logged at ERROR when closing Scarlet while JDA still had in-flight command-registration REST calls (triggered by `updateCommandList()` after a version change) — installed a custom `RestAction.setDefaultFailure` handler in `ScarletDiscordJDA` that routes `InterruptedIOException` down to DEBUG and delegates all other failures to JDA's original default handler
  - Fixed inverted `awaitShutdown` condition in `ScarletDiscordJDA.close()` — the code was force-shutting-down JDA only after graceful shutdown had already succeeded (no-op) and doing nothing when the 10s timeout elapsed (leaving pending requests hanging); now correctly force-shuts-down on timeout and preserves the thread interrupt status on `InterruptedException`
  - Fixed RVC, TTS, and xdg-utils dependency-installer dialogs having their Yes/No buttons clipped off the bottom of the screen on Windows at elevated display scale (125%/150%/200%) — `JOptionPane` dialogs are non-resizable and sized to their content's preferred height, so tall HTML bodies pushed the button row past the screen edge; added a new `Swing.fitToScreen` helper that wraps over-tall content in a screen-aware `JScrollPane` (capped at 70% screen height / 85% width), and applied it to every installer dialog in `RvcInstallDialogs`, `TtsPackageInstallDialogs`, and `XdgOpenInstallDialogs`
  - Changed `TtsService` RVC accessors (`isRvcAvailable`, `getRvcStatus`, `getRvcModelsDir`, `getRvcModels`, `setRvcConfig`) to null-safe so a null `RvcService` in the lite edition is handled gracefully
  - Changed `RvcService.getResourcePath()` to prefer a fresh classpath extraction over the AppData cache, so bundled bridge upgrades take effect on the next launch

## 0.4.16-b4
  - Added built-in `/discord-kick` and `/discord-ban` Discord slash commands using the native Discord member picker
  - Added optional reason parameter to `/discord-kick` and `/discord-ban`
  - Added DM notification sent to the target user before a kick or ban is carried out
  - Added one-time opt-in prompt on first startup asking whether to enable the built-in Discord kick/ban commands
  - Added `discord_kick_ban_enabled` toggle under Settings → Discord
  - Added ephemeral response when `/discord-kick` or `/discord-ban` is used while the feature is disabled, directing the user to Settings
  - Added in-app CLI panel as a dedicated "CLI" tab in the Scarlet UI, with a terminal-style read-only output area and a command input field
  - Added `rawCommand` output routing so CLI results appear in the in-app panel as well as the system log
  - Added "Run CLI command" entry under Settings → CLI for running commands via a popup dialog
  - Added startup banner to the console showing the Scarlet version and a hint to type `help`
  - Fixed CLI `help` command output not being printed to the console
  - Improved logging for Discord kick and ban actions (actor, target, guild, reason all recorded at INFO level)

## 0.4.12
  - Added User Spawn Prop extended event
  - Added notes field for watched entities and groups
  - Added autocomplete for watched entity and group ids
  - Added filter to narrow potential avatars based on author id
  - Updated JDA to 5.6.1 (was 5.2.1)
  - Fixed custom Emojis not being properly detected
  - Fixed some text not being sanitized for Markdown
  - Fixed some events not firing on player joins
  - Fixed watched entities not loading or importing
  - Fixed watched users commands failing and having incorrect links

## 0.4.12-rc8
  - Fixed error in output for banning or unbanning multiple users with the appropriate Discord command
  - Fixed error when custom moderation tags is exactly a multiple of 25

## 0.4.12-rc7
  - Added User Spawn Emoji and Watched Moderation extended events
  - Added Ban User and Unban User buttons to several event embeds (User Switch Avatar, User Spawn Pedestal/Sticker/Print/Emoji)
  - Added Watched Users and Watched Avatars
  - Added user current avatar name to Instance UI
  - Changed maxium number of custom moderation tags to 125 (was 25)
  - Fixed watched entity imports via Discord command using the incorrect option name
  - Fixed mislabeled Discord command descriptions
  - Fixed silenced watched groups overriding TTS messages for lower priority groups

## 0.4.12-rc6
  - Added links to the Sybyline Network VRChat Group
  - Added Group Invites created to Moderation Summary
  - Added option to skip confirmation to send a group invite to players via Instance UI
  - Added deep equality check to avoid editing Discord commands with identical content
  - Changed the `Edit tags` button on moderation events to auto-populate currently assigned tags
  - Fixed custom moderation tags with long descriptions causing some Discord interactions to fail
  - Fixed Instance UI failing to send group invites
  - Fixed instability caused by unexpected/unknown API enum values failing deserialization

## 0.4.12-rc5
  - Added `tag-immediately` option to `vrchat-user-ban` Discord slash command, allowing user to specify tags and description before banning
  - Added user pronouns and status description to moderation event embed
  - Added Invite to Group button to Instance UI
  - Added API call rate limit to Instance UI
  - Changed Instance UI to skip updating during preamble
  - Fixed `permissions` autocomplete suggestions missing for Discord interactions
  - Fixed VRChat Help Desk autofill links missing most information
  - Fixed sticker images not showing up in the User Spawn Sticker extended event embed

## 0.4.12-rc4
  - Added Outstanding Moderation and Action Failure extended events
  - Added `outstanding-moderation`, `submit-evidence`, `vrchat-user-ban-multi`, and `vrchat-user-unban-multi` Discord slash commands
  - Added optional extra filter step to narrow down potential avatars from User Switch Avatar extended event
  - Removed unloaded libraries from distribution
  - Changed api cache default max age
  - Fixed bugs with the intended ephemerality of Discord responses being ignored
  - Fixed bugs with ipc-piped commands not executing

## 0.4.12-rc3
  - Added setting to configure which VRCX-compatible avatar search providers to use
  - Added cli commands via named pipe
  - Added report links to various events and search results
  - Added button to view potential avatar matches for easier manual correlation
  - Changed VRChat Client launching to use URI on non-Windows platforms

## 0.4.12-rc2
  - Added avatar thumbnail to User Switch Avatar extended event (not foolproof, can be default/previous avatar image)
  - Updated VRChat Help Desk autofill links to account for recent changes
  - Fixed bugs with bundled bans/kicks

## 0.4.12-rc1
  - Added setting to launch a new VRChat Client instance into newly created group instances
  - Added option to assign/remove a VRChat Group Role when adding/removing staff from lists
  - Added Discord subcommands `add-role` and `remove-role` to `vrchat-group`
  - Added Discord message command `fix-audit-thread` to be used when moderation event messages fail to populate
  - Changed Discord command `query-target-history` to redact secret staff names in event description
  - Fixed typo in event log messages description header

## 0.4.11
  - Added User Spawn Print extended event
  - Added Bundle ID to User Switch Avatar extended event (when available)
  - Added user age verification status to Instance UI
  - Added title to runner

## 0.4.11-rc11
  - Added User Spawn Pedestal, User Spawn Sticker, and Suggested Moderation extended events
  - Added autocomplete to VRChat User and VRChat World options for Discord commands
  - Changed default Discord commands permissions to `USE_APPLICATION_COMMANDS`

## 0.4.11-rc10
  - Added ban/unban buttons to Vote-to-Kick log entries
  - Added snapshot repository to dependency downloader
  - Changed periodic report to include group joins, leaves, and kicks
  - Fixed crash loading permissions data
  - Fixed bug with empty pagination

## 0.4.11-rc9
  - Internal overhaul of all Discord commands and interactions
  - Added the `/scarlet-discord-permissions` Discord command
  - Added the `/vrchat-search` Discord command
  - Added file lock so multiple instances of Scarlet do not run for the same group at the same time on a given system
  - Added Discord commands (`vrchat-animated-emoji`) that convert gifs into spritesheets for VRChat Gallery animated emoji
  - Removed the `/scarlet-permissions` Discord command (old configuration will not be lost if you update)
  - Changed the `/watched-groups` Discord command to consolidate subcommands
  - Changed Discord command default permissions to requiring only either `MODERATE_MEMBERS` or `MANAGE_SERVER`, depending on the sensitivity of the command
  - Fixed selector in settings tab for evidence root not having any effect

## 0.4.11-rc8
  - Added functionality to paginated Discord command responses
  - Changed WorldBalancer domain from `avatarwb.worldbalancer.duia.us` to `avatar.worldbalancer.com`
  - Fixed `/(secret-)staff-list list` command sometimes failing

## 0.4.11-rc7
  - Added input sanitization/resolution for many Discord commands to accept many intuitive forms for groups, users, worlds, etc. (VRChat Web links, group short codes, etc.)
  - Added `/vrchat-group` Discord command, with `create-instance` and `close-instance` subcommands
  - Added customizability for JVM options via `./java.options` file
  - Removed attachment containing user state to moderation events (will rework to improve usability)

## 0.4.11-rc6
  - Fixed TTS not outputting to Discord voice channel
  - Fixed versions being both missing and in reverse order in `/server-restart` Discord command autocomplete

## 0.4.11-rc5
  - Added tentative support for tailing the VRChat client logs on Linux
  - Added setting to modify the audit polling interval from the canonical 60 seconds
  - Fixed some embeds not having linked titles
  - Fixed secret channels not being set

## 0.4.11-rc4
  - Fixed duplicate avatar ids being logged
  - Fixed embed field having too many characters
  - Fixed misleading output from `run.bat`
  - Fixed an infinite loop of failure `run.bat`

## 0.4.11-rc3
  - Added update option to `/server-restart` Discord command
  - Fixed duplicate alternate field in AvatarSearch.VrcxAvatar

## 0.4.11-rc2
  - Added naive in-memory cache for avatar searches
  - Fixed IllegalArgumentException in TTSService
  - Fixed NullPointerException in staff lists
  - Fixed StackOverflowError in ScarletData

## 0.4.11-rc1
  - Added extended events: User Join, User Leave, User Switch Avatar, and Instance Monitor
  - Added avatar search support (only exposed internally for now)
  - Added support for auto restarting (when runner is used)
  - Added secret staff list: events actored by these users can be diverted to other channels and are not visible to normal staff under most conditions
  - Changed handling of Vote-to-Kick Initiated extended event to properly handle initiator name when applicable
  - Changed Moderation Report extended event to include totals and optionally omit staff without activity
  - Removed the `associate-ids` Discord command in deference to using the staff commands
  - Fixed some extended events not being sent to auxiliary webhooks
  - Fixed Markdown escaping for display names in multiple places

## 0.4.10
  - Added UI scaling
  - Added `joins` field to moderation summary
  - Changed templating for VRChat Help Desk autofill to optionally append footer
  - Changed TTS to replace some special Unicode characters before generating audio
  - Added primitive input sanitization of the Group ID
  - Fixed some users having negative account age in Instance UI
  - Fixed AcctAge column of Instance UI comparing by textual instead of numerical value

## 0.4.10-rc5
  - Added daily moderation summary with configurable time-of-day to generate
  - Added templating for VRChat Help Desk autofill
  - Added TTS announcements for Votes-to-Kick Initiated
  - Added tentative search of the system path for different PowerShell executable names

## 0.4.10-rc4
  - Added location to extended event embeds
  - Changed evidence submission to not require a direct reply, but instead to just be in the same thread

## 0.4.10-rc3
  - Added settings for enabling and selecting the evidence submission folder
  - Added listing auxiliary webhooks to `config-info`
  - Fixed some settings not getting created as default if they didn't exist

## 0.4.10-rc2
  - Fixed Email OTP (again)
  - Added Instance Inactive extended event
  - Staff mode now properly ignores audit log polling and related systems
  - Fixed app hanging on startup if no VRChat logs were found
  - Fixed log parsing for Vote-to-Kick Initiated
  - Fixed data not getting saved
  - Fixed the `/set-audit-ex-channel` having a typo in one of its options

## 0.4.10-rc1
  - Added staff list command `/staff-list`
  - Added extended events: Staff Join, Staff Leave, and Vote-to-Kick Initiated
  - Added audit event bundling: if a User Ban results in a Instance Kick, then the latter is logged with the former by default
  - Changed the command suggestions to display more human-friendly names over internal identifiers
  - Fixed Discord roles being mistakenly formatted like Discord users
  - Fixed moderation events initiated with commands or buttons not properly referencing the initiating user

## 0.4.9
  - Added ability to redact audit events: they do not count in the running sum of moderation events for embeds
  - Fixed autocomplete for the `scarlet-permission` Discord command not working
  - Fixed manual command update button not working

## 0.4.9-rc3
  - Added ability to revert instance UI to default ordering by right clicking header
  - Changed Discord command `set-permission-role` to `scarlet-permission` and added support for multiple roles
  - Fixed bug (#9) that tried to set a Discord embed field value to more than 1024 characters

## 0.4.9-rc2
  - Added application version change detection
  - Changed Discord bot to only send command list if version changes
  - Changed instance UI to only sort once the log tailing/parsing has caught up 
  - Changed watched groups sort order to prioritize `priority` instead of the flags `critical` and `silent`
  - Fixed NPE when emitting aux webhooks for certain events
  - Fixed watched groups added via Discord commands missing the `id` field, causing them to magically disappear

## 0.4.9-rc1
  - Fixed bug: Discord bot responds properly to the `set-audit-aux-webhooks` command
  - Updated dependency: com.github.vrchatapi:vrchatapi-java:1.18.9 to version 1.19.1

## 0.4.8
  - Added ability to silence TTS messages for particular groups
  - Added ability to import watched groups from UI
  - Fixed bug: removed references to restricted/internal api
  - Fixed regression: switched emailotp and totp auth flows

## 0.4.8-rc1
  - Added experimental "staff mode" (discord integration disabled, data like watched groups must be synced manually)
  - Added ability to ban and unban VRChat users with desktop UI, Discord buttons, and Discord commands
  - Added ability to disable pings on Discord for moderation audit events
  - Added priority system to groups
  - Added default sorting for instance UI
  - Added colored text based on group watch type for instance UI
  - Added ability to forward audit event messages to other Discord servers via webhooks
  - Added AuditID to autopopulation of description field for VRChat Help Desk Links
  - Added support for Email OTP
  - Fixed TTS subprocess hang if selected TTS voice isn't installed on the system
  - Fixed regression: implicit class loading from main class happened before dependencies were downloaded

## 0.4.7
  - Fixed regression: TOTP codes incorrectly reporting as invalid

## 0.4.6
  - Added splash screen
  - Added update check popup
  - Added automatic disabling log parser if VRChat client not present
  - Fixed auth refresh not triggering
  - Fixed setting TTS voice not working or persisting
  - Changed UI theming

## 0.4.6-rc1
  - Added list of users in current instance to the UI
  - Added various links to the UI
  - Added ability for group to autopopulate email field for VRChat Help Desk Links
  - Fixed watched groups not being properly detected

## 0.4.5
  - Added caching layer for some VRChat API requests
  - Added some autocomplete for the `watched-group` command
  - Added initial text `Unclaimed` to new moderation events
  - Fixed missing reprompt on invalid credentials
  - Fixed incorrect gateway intent in instructions

## 0.4.5-rc4
  - Added ability to specify where Scarlet stores data
  - Added ability to select a TTS voice
  - Added Discord commands to configure watched groups
  - Added Discord command to export Scarlet logs
  - Added dummy UI for ease of exiting the application
  - Added default command permissions for Discord commands
  - Changed log name format for JDA classes

## 0.4.5-rc1
  - Fixed NPE when checking if a joining player has watched groups

## 0.4.4
  - Fixed importing legacy CSV list of watched groups (Excel format, instead of RFC4180)

## 0.4.3
  - Added step to installation instructions to make discord bot private
  - Added newer version check
  - Added more build automation
  - Fixed discord bot responding to interactions from servers other than the server specified in configuration
  - Fixed discord bot sometimes not automatically rejoining voice channel
  - Fixed some errors not printing to log file
  - Changed link to download a JDK from potentially confusing Github link to Adoptium website proper

## 0.4.2
  - Added important step to installation instructions
  - Added `pause` line to runner
  - Removed input prompt for Discord audio channel snowflake
  - Fixed bug in dependency downloader not properly unescaping characters
  - Changed Java version check to warn instead of terminating application

## 0.4.1
  - Added logger thread and log files
  - Added importing watched groups legacy CSV via attachment submissions
  - Fixed bug where the process could hang upon quitting
  - Fixed typos and missing comments
  - Changed some logging levels
  - Changed handling of tags for importing watched groups legacy CSV

## 0.4.0
  - Added installation and usage instructions to README.md
  - Added CHANGELOG.md
  - Added SETTINGS.md
  - Added automation for building a zip
  - Added runtime check for JVM's Java version
  - Added ability to automatically generate TOTP code from secret
  - Added link and message reference to group audit event `group.instance.close`
  - Added TTSService to announce events in a Discord voice channel
  - Added VRChat log tailing/parsing
  - Added Watched Groups, which generate TTS announcements when members' joining is detected
  - Removed unused code and resources from automatic dependency downloader
  - Renamed `submit-evidence` Discord message command to `submit-attachments`
  - Fixed audit event embed color overrides being ignored
  - Fixed typos

## 0.3.3
  - Fixed escaping for VRChat Help Desk autofill link generation
  - Added role-associated permission system for interacti