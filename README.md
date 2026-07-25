[discord-invite]: https://discord.gg/CP3AyhypBF
[discord-widget]: https://discord.com/api/guilds/1342131776876838912/widget.png

[![Discord Widget][discord-widget]][discord-invite]

<img src="images/sybyline_scarlet.png?raw=true" alt="Scarlet logo" width="300" height="300"/>

# Scarlet

A self-hostable VRChat Group management utility with Discord integration.

- [VRChat Group](https://vrchat.com/home/group/grp_f12667c7-df5f-454f-9a34-5ed8c33112a1)
- [Changelog](CHANGELOG.md)
- [Settings](SETTINGS.md)
- [Frequently Asked Questions](FAQ.md)
- [Installation](#installation)

---

## Why Scarlet?

Scarlet is self-hosted, meaning you have complete control of your group's data.  
There is no third party with access to your VRChat or Discord credentials or other sensitive information.  
Since there is no automatic synchronization of data between groups running Scarlet, you don't have to worry about other groups seeing who your group has moderated or what groups you are tracking.

---

## Highlights

- **Cross-platform.** Runs on Windows and Linux (this is the Linux-compatible fork). The desktop app is Java/Swing; the VRChat-log features work when VRChat runs on the same machine, including under Proton/Wine on Linux.
- **Near-instant moderation posts.** Moderation actions (Discord command, UI, or bulk-ban queue) and in-game warns/kicks/bans post to Discord within seconds via burst polling, then settle back to the idle interval.
- **Watched users, groups, and avatars** with per-category advisories, spoken TTS callouts, native desktop notifications, and optional mobile push — each independently toggleable.
- **Avatar statuses with or without VRChat's `[API]` launch options.** When the launch options aren't set, Scarlet reconstructs avatar/performance info through avatar-search databases plus current-avatar-image confirmation and the VRChat API, and tells you honestly when something can't be resolved.
- **Instance creation and management** for both group instances (Public/Group+/Members) and personal instances (Public/Friends+/Friends/Invite+/Invite), with queue/age-gate/content presets, invite-a-friend, and self-invite when VRChat is already running.
- **Multi-language UI (English and Russian complete).** The whole desktop client — including spoken TTS callouts — follows your OS language or a chosen one; the Discord bot replies in each user's own Discord locale. Community translations drop into a `lang/` folder with no rebuild.
- **Training mode.** A settings toggle enables an event simulator so a trainer can run realistic drills — including practicing the Discord tagging workflow — without anyone joining a bad group, fully isolated from live moderation (see **Training mode** below).
- **Reliability watchdogs.** Scarlet detects a stalled VRChat log tailer or an expired session, attempts unattended re-login, and can route health alerts to a staff Discord channel — so it never fails silently. Data files save crash-safely with dated backups.
- **Migration bundles, a diagnostics view, and a searchable friend/invite picker** round out the desktop tooling.

---

## Features

### Discord Commands

#### Moderation Commands

- **`create-or-update-moderation-tag <value:string> <label:string?> <description:string?>`**  
  Adds or updates a custom moderation tag (max of 125).  
  Example: `/create-or-update-moderation-tag "trolling" "Trolling" "Provocative or mocking behavior intended to antagonize someone"`

- **`delete-moderation-tag <value:string>`**  
  Removes a custom moderation tag (max of 125).  
  Example: `/delete-moderation-tag "trolling"`

- **`watched-user`**  
  Configures watched users.  
  Example: `/watched-user add-tag "usr_00000000-0000-0000-0000-000000000000" "inappropriate_behaviour"`

- **`watched-group`**  
  Configures watched groups.  
  Example: `/watched-group add-tag "grp_00000000-0000-0000-0000-000000000000" "trolling"`

- **`watched-avatar`**  
  Configures watched avatars.  
  Example: `/watched-avatar add-tag "avtr_00000000-0000-0000-0000-000000000000" "missing_content_tags"`

- **`vrchat-user-ban <vrchat-user:string>`**  
  Ban a specific VRChat user.  
  Example: `/vrchat-user-ban "usr_00000000-0000-0000-0000-000000000000"`

- **`vrchat-user-ban-multi`**  
  Ban several VRChat users.  
  Example: `/vrchat-user-ban-multi`

- **`vrchat-user-unban <vrchat-user:string>`**  
  Unban a specific VRChat user.  
  Example: `/vrchat-user-unban "usr_00000000-0000-0000-0000-000000000000"`

- **`vrchat-user-unban-multi`**  
  Unban several VRChat users.  
  Example: `/vrchat-user-unban-multi`

- **`vrchat-user-info <vrchat-user:string>`**  
  Lists internal and audit information for a specific VRChat user.  
  Example: `/vrchat-user-info "usr_00000000-0000-0000-0000-000000000000"`

- **`vrchat-group`**  
  Manages group operations such as member search, ban/invite/request lists, posts, announcements, audit-type discovery, instance tools, role assignment, and owner-level transfer checks. Instance creation includes queue, age gate, content, and avatar performance gate presets.  
  Examples: `/vrchat-group search-members "display name"`, `/vrchat-group list-bans`, `/vrchat-group audit-types`, `/vrchat-group create-post`

- **Desktop instance wizard**  
  Use **Create Instance** in Scarlet's top action bar, or **File -> Create VRChat group instance...**, to create a group instance from a world URL/id and optionally open it in the VRChat client in VR or Desktop mode.

- **`discord-user-info <discord-user:user>`**  
  Lists internal information for a specific Discord user.  
  Example: `/discord-user-info <@123456789123456789>`

- **`discord-warn <discord-user:user> <reason:string?>`**  
  Sends a warning DM to a Discord server member and records it in the configured action log channel.  
  Example: `/discord-warn <@123456789123456789> "Stop spamming chat"`

- **`discord-kick <discord-user:user> <reason:string?>`**  
  Kicks a Discord server member after confirmation and records the result in the configured action log channel.  
  Example: `/discord-kick <@123456789123456789> "Repeated spam"`

- **`discord-ban <discord-user:user> <reason:string?>`**  
  Bans a Discord server member after confirmation and records the result in the configured action log channel.  
  Example: `/discord-ban <@123456789123456789> "Raid account"`

- **`submit-evidence <evidence-submission:attachment> <evidence-submission-2:attachment?> ...`**  
  Submit attachments for evidence.  
  Example: `/submit-evidence <(attached file)>`

#### Audit and Logging Commands

- **`query-target-history <vrchat-user:string> <days-back:int?>`**  
  Queries audit events targeting a specific VRChat user.  
  Example: `/query-target-history "usr_00000000-0000-0000-0000-000000000000" "14"`

- **`query-actor-history <vrchat-user:string> <days-back:int?>`**  
  Queries audit events performed by a specific VRChat user.  
  Example: `/query-actor-history "usr_00000000-0000-0000-0000-000000000000" "14"`

- **`set-audit-channel <audit-event-type:string> <discord-channel:channel?>`**  
  Sets a given text channel as the channel certain audit event types use.  
  Example: `/set-audit-channel "group.instance.kick" <#log-instance-kicks>`

- **`set-audit-aux-webhooks <audit-event-type:string>`**  
  Sets the given webhooks as the webhooks certain audit event types use.  
  Example: `/set-audit-aux-webhooks "group.instance.kick"`

- **`set-audit-ex-channel <audit-ex-event-type:string> <discord-channel:channel?>`**  
  Sets a given text channel as the channel certain extended event types use.  
  Example: `/set-audit-ex-channel "groupex.instance.vtk" <#log-instance-kicks>`

- **`set-audit-secret-channel <audit-event-type:string> <discord-channel:channel?>`**  
  Sets a given text channel as the secret channel certain audit event types use.  
  Example: `/set-audit-secret-channel "group.instance.kick" <#log-instance-kicks>`

- **`set-audit-ex-secret-channel <audit-ex-event-type:string> <discord-channel:channel?>`**  
  Sets a given text channel as the secret channel certain extended event types use.  
  Example: `/set-audit-ex-secret-channel "groupex.instance.vtk" <#log-instance-kicks>`

- **`set-discord-action-log-channel <discord-text-channel:channel?>`**  
  Sets the channel for Discord warn/kick/ban result logs and Discord member join invite logs. Omit the channel to disable this log. Invite tracking requires the bot to have Manage Server permission and the Discord member intent enabled; Discord does not expose member IP addresses to bots.  
  Example: `/set-discord-action-log-channel <#discord-mod-log>`

- **`set-ops-alert-channel <discord-text-channel:channel?>`**  
  Sets a channel to receive Scarlet's operational health alerts — VRChat session lost/recovered, log tailer stalled/recovered, hard VRChat rate-limiting, a Scarlet update becoming available, and VRChat API version mismatches — so staff learn about problems in Discord instead of only from the desktop app. Omit the channel to disable.  
  Example: `/set-ops-alert-channel <#scarlet-health>`

- **`set-training-channel <discord-text-channel:channel?>`**  
  Sets a channel to receive simulated `[TRAINING]` events from Training mode, keeping drills out of your real audit log. If unset, simulated events are not posted to Discord. Omit the channel to disable.  
  Example: `/set-training-channel <#scarlet-training>`

- **`set-discord-account-age-alert <days:int?>`**  
  Sets a Discord account-age threshold; members whose account is newer are flagged in the join log. Omit to clear.  
  Example: `/set-discord-account-age-alert "7"`

- **`moderation-summary <hours-back:int?>`**  
  Generates a summary of moderation actions.  
  Example: `/moderation-summary "48"`

- **`outstanding-moderation <hours-back:int?>`**  
  Generates a list of outstanding moderation actions.  
  Example: `/outstanding-moderation "48"`

#### Configuration Commands

- **`set-voice-channel <discord-channel:channel?>`**  
  Sets a given voice channel as the channel in which to announce TTS messages.  
  Example: `/set-voice-channel <#staff-in-instance>`

- **`set-tts-voice <voice-name:string>`**  
  Sets the voice in which to announce TTS messages.  
  Example: `/set-tts-voice "Microsoft David Desktop"`

- **`scarlet-permission <scarlet-permission:string> <discord-role:role?>`**  
  Sets a given Scarlet-specific permission to be associated with certain Discord roles.  
  Example: `/scarlet-permission add-to-role "event.set_tags" <@123456789123456789>`

- **`config-info`**  
  Shows information about the current configuration.  
  Example: `/config-info`

- **`config-set`**  
  Configures miscellaneous settings.  
  Example: `/config-set mod-summary-time-of-day "-06:00"`

- **`link-vrchat-account` / `unlink-vrchat-account`**  
  Lets a server member link (or unlink) their own Discord account to their VRChat account. Group staff can unlink another member with `unlink-vrchat-account-for`.  
  Example: `/link-vrchat-account`

- **`set-training-channel`, `set-ops-alert-channel`** — see the Audit and Logging section above.

#### Utility Commands

- **`vrchat-search <world|user|group|avatar> <search-query:string>`**  
  Search for VRChat content.  
  Example: `/vrchat-search user "Vinyarion"`

- **`export-log <file-name:string?>`**  
  Exports a Scarlet log file as an attachment.  
  Example: `/export-log`

- **`server-restart`**  
  Restarts the Scarlet server application.  
  Example: `/server-restart`

- **`vrchat-animated-emoji`**  
  Generates a VRChat animated emoji spritesheet from a gif.  
  Example: `/vrchat-animated-emoji from-url "https://tenor.com/view/rat-spin-gif-10300642414513246571"`

---

### CLI Commands

- **`exit`, `halt`, `quit`, `stop`**  
  Shuts down the application.  
- **`logout`**  
  Logs out of the VRChat account and shuts down the application.  
- **`explore`**  
  Browses to the folder Scarlet uses to store data.  
- **`tts <message...>`**  
  Queues a TTS message to be read in the Discord Voice channel, if connected.  
- **`link <vrcUserId> <discordUserSnowflake>`**  
  Associates a VRChat account with a Discord account.  
- **`importgroups <file|url...>`**  
  Imports a legacy CSV list of watched groups from a file or url.  
- **`importgroupsjson <file|url...>`**  
  Imports a JSON list of watched groups from a file or url.  
- **`simulate <kind> [name]`**  
  Fires a simulated training event (requires Training mode enabled). Kinds: `join`, `watched`, `wuser`, `new`, `mixed`, `pronouns`, `avatar`, `vtk`, `leave`.  
- **`langlint`**  
  Validates the external `lang/messages_<lang>.properties` translation files against the English base and prints a per-file report (missing keys, unknown keys, broken `{0}` placeholders).  
- **`reboot`, `restart`**  
  Restarts the application.

Scarlet supports sending cli commands via named pipes.
Windows: `\\.\pipe\ScarletIPC-grp_00000000-0000-0000-0000-000000000000`
Everything else (Unix-like): `/tmp/ScarletIPC-grp_00000000-0000-0000-0000-000000000000.sock`

```ps1
function Send-ScarletIPC
{
    param
    (
    [String]$GroupID,
    [String]$Message
    )
    $request = [System.Text.Encoding]::UTF8.GetBytes($Message);
    $stream = New-Object -TypeName System.IO.Pipes.NamedPipeClientStream -ArgumentList '.',"ScarletIPC-$GroupID",([System.IO.Pipes.PipeDirection]::Out),([System.IO.Pipes.PipeOptions]::None),([System.Security.Principal.TokenImpersonationLevel]::Impersonation)
    $stream.Connect(1000);
    $stream.Write($request, 0, $request.Length);
    $stream.Dispose();
}

Send-ScarletIPC -GroupID 'grp_00000000-0000-0000-0000-000000000000' -Message 'stop'
```

---

### Interoperability

Scarlet links to the official VRChat website whenever possible, including for user profiles, group posts, instances, and much more.  
Scarlet can generate a link that will autopopulate the fields of the VRChat Help Desk report form based on custom tags assigned to moderation events, streamlining the reporting process and reducing mistakes from human error.

---

### Localization

- The desktop UI and the spoken TTS callouts are translated. **English and Russian** ship complete; German, Spanish, and Indonesian are in-progress pilots that fall back to English for anything not yet translated.
- Pick a language in **Settings -> Appearance** (a dropdown of native names); it defaults to your operating system language and applies on restart. The Discord bot renders each reply in the invoking user's own Discord language.
- **Community translations without a rebuild:** drop a `messages_<lang>.properties` file into the `lang/` folder in Scarlet's data directory and restart — new languages are auto-discovered, and Scarlet writes an up-to-date English `messages.template.properties` there as a starting point. The `langlint` CLI command (and a startup check) validates community files, flagging missing keys and broken `{0}` placeholders.

---

### Training mode

- Enable **Settings -> Training** to unlock an event simulator (Edit -> *Simulate event (training)...*, or the `simulate` CLI command) that fires realistic events on demand — so new moderators can learn on a screenshare instead of a live incident, and nobody has to actually join a bad group.
- Simulated events run the **real** pipeline — the player-list row, TTS callout, desktop/mobile notification, and a genuine Discord post with its ban/unban buttons — so the whole workflow, including tagging in Discord, is practiceable.
- While training, Scarlet behaves as a **separate client**: the real instance is parked and kept updated in the background, actions on training players show real success feedback but make no VRChat call, and turning training off restores the live instance instantly. Everything training-related is marked `[TRAINING]` and, with `set-training-channel`, posts to its own channel — a drill can never be mistaken for a real record.

---

### Reliability and health monitoring

- A background watchdog turns Scarlet's silent failure modes into loud, recoverable ones: if the VRChat log goes quiet while the game is running, or the VRChat session expires mid-run, Scarlet raises a popup, attempts unattended re-login, and (with `set-ops-alert-channel`) posts a Discord health alert.
- Data files (watched lists, settings, Discord config, moderation tags, ...) are written crash-safely and keep the last several dated backups in a `backups/` folder beside each file, so a crash mid-write can't lose a blocklist.
- The **Diagnostics** view (Help -> Scarlet: Diagnostics...) shows connectivity, rate-limit, and avatar-provider status without making extra API calls.

---

### Desktop and mobile notifications

- Advisories can raise native desktop notifications (Windows Action Center, Linux `notify-send`, macOS `osascript` — PowerShell is never invoked), configured per category in **Settings -> Desktop Notifications**.
- A mobile companion can push the same alerts to a phone; pair it from **Settings -> Mobile Companion**.

---

### About Extended Events

Extended audit events (Instance Inactive, Staff Join, Staff Leave, Vote-to-Kick Initiated) are logged with the Discord command `set-audit-ex-channel`.  
Some of these require that a VRChat Client in a group instance must be running on the same machine in order for them to be logged in Discord channels.  
This limitation exists because Scarlet reads the VRChat client log file as it gets updated with information.  
At the moment, these events are not of the same degree as the canonical group audit events, as they are derived from the local client log rather than a server-side audit record, but similar functionality may hopefully be added to VRChat's first-party API in the future.

---

### About Actors and Targets

Actors are the users that *initiated or performed* an event, like a group staff member.  
Targets are the users that had an event *performed on them*, like the user a group staff member kicks from an instance.  
For some events, the target may not be a user, like for when a group instance is created.

---

## Installation

### Requirements

You will need:  
- A VRChat group you (or a dedicated bot account) have permissions for.  
- A Discord server (guild) in which you have permissions.  
- A Discord bot account (application).  
- A Windows or Linux PC with Java 8 (or newer) installed.

Scarlet runs on both Windows and Linux (this is the Linux-compatible fork). The Discord and VRChat-API features work anywhere; the VRChat-log features (instance monitoring, extended events, avatar statuses) require the VRChat client to be running on the same machine — natively on Windows, or under Proton/Wine on Linux.

If you wish to not install Java for all users on the PC, if you would like to have several different Java installations, or if you would otherwise prefer to keep the Java installation to Scarlet only, see the instructions further below.

Thanks to [@KozyBlake](https://github.com/KozyBlake) for making this video tutorial:

[![installation-tutorial-video](https://img.youtube.com/vi/JMJgMSThBac/0.jpg)](https://www.youtube.com/watch?v=JMJgMSThBac)

---

### Setting up Discord Application

It is recommended that you create a bot account dedicated specifically for running Scarlet.  
Create a new application via the Discord developer dashboard: https://discord.com/developers/applications

Scarlet requires some permissions above the bare defaults:  
1. In the `Installation` tab for your app:  
    - Scarlet only supports installation for servers.  
      Ensure that only the `Guild Install` box is checked in the `Installation Contexts` area.  
      ![setup installation contexts](images/setup_installation_contexts.png?raw=true)  
    - In the `Guild Install` part of the `Default Install Settings` area:  
      - Add the `bot` scope.  
      - Add the `Attach Files`, `Create Polls`, `Create Public Threads`, `Embed Links`, `Manage Webhooks`, `Read Message History`, `Send Messages`, `Send Messages in Threads`, `Speak`, and `View Channels` permissions.  
        ![setup default install settings](images/setup_default_install_settings.png?raw=true)  
2. In the `Bot` tab:  
    - Enable the `Server Members` and `Message Content` intent in the `Privileged Gateway Intents` area.  
      ![setup privileged gateway intents](images/setup_privileged_gateway_intents.png?raw=true)  
3. Invite the bot to your server.  
4. In the `Installation` tab for your app, select the `None` option for the `Install Link` area.  
   ![setup install link](images/setup_install_link.png?raw=true)

---

### Setting up the VRChat Group

The VRChat account that Scarlet will use must have at least these permissions:  
- `Manage Group Member Data`  
- `View Audit log`  
- `View All Members`

At the moment, Scarlet does not enforce any moderation action against users.  
All such actions (e.g., kicking or banning a user) must be performed manually, but Scarlet provides some convenience methods like Discord commands and buttons.

---

### Installing the Scarlet Desktop Application

1. Download the latest release (`zip` is recommended): https://github.com/KozyBlake/Scarlet/releases/latest  
2. Copy or extract the files into the directory of your choosing.  
3. If you have Java 8 (or newer) installed to the system PATH, skip this step.  
    - Download and extract a Java 8 JDK, such as one from [Adoptium](https://adoptium.net/temurin/releases/?package=jdk&version=8) (pick your OS/arch — Windows or Linux).  
    - Create (or overwrite) the file `scarlet.home.java` next to `run.bat`.  
    - Copy-paste etc. the root path of the JDK to the **first line** of `scarlet.home.java` and save the file.  
4. If you want Scarlet to store data in a specific folder:  
    - Create (or overwrite) the file `scarlet.home` next to `run.bat`.  
    - Copy-paste etc. path of the desired directory to the **first line** of `scarlet.home` and save the file.  
5. Start Scarlet: on Windows run `run.bat`; on Linux run the included launch script (or start the jar directly with your Java 8+ runtime).
