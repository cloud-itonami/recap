# etzhayyim-project-recap — Multi-Platform Media Download Agent

## App Identity

| Key | Value |
|---|---|
| **nanoid** | `r3c4p001` |
| **domain** | `recap.etzhayyim.com` |
| **AT bot DID** | `did:web:recap.etzhayyim.com` |
| **Runtime** | CF Worker (L3 dispatcher) → LangGraph Server pod (L8) |
| **Scope** | **社内研究・教育用途 (fair-use only)** — arbitrary public download は禁止 |
| **Backend** | `lg-recap` FastAPI + LangGraph (同 lg-animeka パターン) |

## Supported Platforms (yt-dlp)

YouTube · TikTok · Instagram Reels · Twitter/X · NicoNico · Bilibili · Vimeo · Twitch · Facebook · Reddit video

## Architecture

```
User / yoro MCP Tool
  → XRPC com.etzhayyim.apps.recap.{download,getInfo,listDownloads}
    → CF Worker (recap.etzhayyim.com) — L3 thin dispatcher
      → DISPATCHER_URL = http://lg-recap:8000/xrpc/{nsid}
        → LangGraph FastAPI server (lg-recap pod, mitama-udf namespace)
          → download graph: validate_url → get_metadata → select_format
                            → download_and_upload (yt-dlp + B2 boto3)
                            → write_record (psycopg → RisingWave :4566)
```

## LangGraph Graphs

| Graph | NSID | Description |
|---|---|---|
| `health` | — | liveness |
| `download` | `com.etzhayyim.apps.recap.download` | Full download → B2 → record |
| `get_info` | `com.etzhayyim.apps.recap.getInfo` | Metadata only (yt-dlp --dump-json) |
| `list_downloads` | `com.etzhayyim.apps.recap.listDownloads` | Paginated history |

## Data Model

### vertex_recap_download (RisingWave)
```sql
vertex_id VARCHAR PK  -- at://{did}/com.etzhayyim.apps.recap.download/{rkey}
rkey VARCHAR
owner_did VARCHAR
actor_did VARCHAR      -- ADR-0095
org_did VARCHAR        -- ADR-0095
at_did VARCHAR
source_url TEXT
platform VARCHAR       -- youtube | tiktok | instagram | x | niconico | bilibili | vimeo | twitch | facebook | reddit
title TEXT
duration_sec INTEGER
format_id VARCHAR
format_note VARCHAR
blob_key VARCHAR       -- B2 SHA-256 key (null until upload complete)
blob_size_bytes BIGINT
thumbnail_url TEXT
uploader VARCHAR
upload_date VARCHAR
status VARCHAR         -- queued | downloading | done | error
error_msg TEXT
scope VARCHAR          -- research | authorized (fair-use policy)
created_at VARCHAR
```

## Commands (MCP Tools)

| Command | Type | Description |
|---|---|---|
| `download` | procedure | URL → yt-dlp → B2 → AT Record |
| `getInfo` | procedure | URL → yt-dlp --dump-json (no download) |
| `listDownloads` | query | Paginated download history |

## Policy

- **Fair-use enforcement**: `ALLOWED_SCOPES = {"research", "authorized"}` in LangGraph validate_url node
- **Platform allowlist**: enforced in validate_url — NSFW-only platforms rejected
- **Disclaimer**: all AT Record `scope` field must be `"research"` or `"authorized"`
- AT records are T2 Domain (`com.etzhayyim.apps.recap.*`) — written via psycopg direct

## Build & Deploy

```bash
# CF Worker
cd 60-apps/etzhayyim-project-recap
etzhayyim deploy

# LangGraph server image
cd 60-apps/etzhayyim-project-recap/lg
docker buildx build --platform linux/amd64 \
  --build-context py=../../../40-engine/kotoba/crates/kotoba-kotodama/py \
  -t ghcr.io/etzhayyim/lg-recap:0.1.0-amd64 --push .

# Helm deploy (mitama-udf namespace, Vultr VKE)
helm upgrade --install lg-recap-pool 50-infra/vultr/lg-recap-pool \
  -n mitama-udf --wait
```
