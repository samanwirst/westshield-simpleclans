WIUT compatibility fork of SimpleClans 2.19.2 for Purpur 26.2 build 2633 / Java 25.

- Fix player locale reads through the supported Paper API.
- Apply clan group permissions immediately for newly created clans.
- Include a minimal player permission allowlist and native GriefPrevention trust configuration.
- 58 isolated full-stack checks passed with three non-OP Java protocol accounts, including actual block/chest/chat packets, AuthMe, exclusion, owner-offline access, world transitions and clean restart.
- Additional native lever checks confirm mechanism-only clan trust without build/chest rights and immediate revocation.
- Physical Java/Bedrock GUI, mobile controls and voice remain unverified.

Source, build instructions, dependency hashes and test scope are included. The build is GPL-3.0 and retains upstream attribution. Java 25 or later is required.
