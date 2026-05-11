# Security Policy

## Reporting Security Issues

Please report suspected Scarlet vulnerabilities privately through GitHub's security reporting flow when available, or through a private maintainer contact channel. Avoid publishing exploit details in public issues until a fix is available.

## Current Advisory

### CVE Pending / Not Assigned: Announcement Custom-Protocol Link Handling

Affected code: development builds containing the new GitHub-backed announcement popup before the announcement URL allowlist.

Impact: announcement metadata could include a local file URL or custom protocol URL. If a maintainer or compromised push-capable account published that metadata and a user clicked `Open link`, Scarlet could pass the URI to the operating system through Java Desktop integration. Depending on local browser, OS, and protocol-handler settings, this could invoke a local application or registered protocol handler.

Mitigation: Scarlet now allowlists only `http://` and `https://` announcement links. The private announcement publisher also rejects non-web URL schemes before writing `meta.json`.

Notes: announcement links are never opened automatically; the issue requires control of the announcement metadata source and user interaction.
