# AE Inventory Dashboard

- Reply in Chinese. Keep code comments in concise English; preserve existing comments.
- Runtime: Minecraft 1.20.1, Forge 47.4.16, AE2 15.4.10, Java 17.
- The Maven AE2 artifact is mapped for development. Production smoke tests must use the separately verified runtime JAR; see docs/SERVER_BASELINE.md.
- Read docs/IMPLEMENTATION_PLAN.md and docs/ACCEPTANCE.md before changes.
- Web uses MVVM: models contain data, viewmodels own state and requests, views render it.
- AE/Minecraft objects stay on their owner thread. HTTP never scans networks. Queues and caches must be bounded.
- No resource pack is required for collection, persistence, history or any dashboard operation.
- No game writes or asset uploads through HTTP. Live deployment requires separate authorization.
- Do not commit worlds, inventories, credentials, resource packs, databases, runtimes or build caches.
- Check relevant tests and git diff --check before each focused commit. Never claim unrun integration tests passed.
