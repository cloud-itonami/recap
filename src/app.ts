// recap.etzhayyim.com — multi-platform media download agent (research/education).
// L3 thin edge dispatcher: forwards XRPC to lg-recap LangGraph server.
// Policy: fair-use only (research/authorized scope). Arbitrary public download prohibited.

import {
  asAgentTool, createWorkerExport, nsid, withCapabilityTags,
  type HostSDK,
} from "@etzhayyim/kotodama-host-sdk";

export default createWorkerExport((sdk: HostSDK) => {
  const env = sdk.env as Record<string, unknown>;

  // ── summarize ───────────────────────────────────────────────────────────────
  sdk.app.command(
    nsid("com.etzhayyim.apps.recap.summarize"),
    async (_ctx, body) => proxyToLg(env, "com.etzhayyim.apps.recap.summarize", decode(body)),
    asAgentTool("Extract transcript and summarize a video. Provide a URL (YouTube, TikTok, NicoNico, etc.) and optionally a lang code (default: ja). Returns structured summary with overview, key points, and conclusion."),
    withCapabilityTags("video", "transcript", "summarize", "yt-dlp", "llm"),
  );

  // ── download ────────────────────────────────────────────────────────────────
  sdk.app.command(
    nsid("com.etzhayyim.apps.recap.download"),
    async (_ctx, body) => proxyToLg(env, "com.etzhayyim.apps.recap.download", decode(body)),
    asAgentTool("Download video/audio from a supported platform (YouTube, TikTok, Instagram, X, NicoNico, Bilibili, Vimeo, Twitch, Facebook, Reddit) to B2 storage. Returns blob key and AT record URI. scope must be 'research' or 'authorized' (fair-use only)."),
    withCapabilityTags("video", "download", "yt-dlp", "b2", "multi-platform"),
  );

  // ── getInfo ─────────────────────────────────────────────────────────────────
  sdk.app.command(
    nsid("com.etzhayyim.apps.recap.getInfo"),
    async (_ctx, body) => proxyToLg(env, "com.etzhayyim.apps.recap.getInfo", decode(body)),
    asAgentTool("Fetch media metadata from a supported platform without downloading. Returns title, uploader, duration, available formats, thumbnail URL."),
    withCapabilityTags("video", "metadata", "yt-dlp"),
  );

  // ── listDownloads ────────────────────────────────────────────────────────────
  sdk.app.query(
    nsid("com.etzhayyim.apps.recap.listDownloads"),
    async (_ctx, body) => proxyToLg(env, "com.etzhayyim.apps.recap.listDownloads", decode(body)),
  );
});

// ── helpers ──────────────────────────────────────────────────────────────────

function decode(body: unknown): Record<string, unknown> {
  if (!body) return {};
  if (body instanceof Uint8Array || Array.isArray(body)) {
    try {
      const bytes = body instanceof Uint8Array ? body : new Uint8Array(body as number[]);
      return bytes.length ? JSON.parse(new TextDecoder().decode(bytes)) : {};
    } catch { return {}; }
  }
  if (typeof body === "object") return body as Record<string, unknown>;
  return {};
}

async function proxyToLg(
  env: Record<string, unknown>,
  nsidStr: string,
  body: Record<string, unknown>,
): Promise<string> {
  const base = ((env.DISPATCHER_URL as string | undefined) ?? "https://dispatcher.etzhayyim.com").replace(/\/+$/, "");
  const headers: Record<string, string> = { "content-type": "application/json" };
  const secret = await resolveSecret(env.DISPATCHER_INTERNAL_SECRET);
  if (secret) headers["x-internal-trust"] = secret;
  const resp = await fetch(`${base}/xrpc/${nsidStr}`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  return resp.text();
}

async function resolveSecret(s: unknown): Promise<string> {
  if (!s) return "";
  try { return typeof s === "string" ? s : await (s as { get(): Promise<string> }).get(); }
  catch { return ""; }
}
