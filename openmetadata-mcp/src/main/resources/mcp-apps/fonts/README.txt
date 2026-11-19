Fonts for the MCP Apps cards, served by the OpenMetadata server at /mcp-assets/fonts/ (McpCardFontsFilter).

inter-*.woff2        Inter variable (wght 100-900), SIL Open Font License 1.1, see Inter-OFL-1.1.txt
roboto-mono-*.woff2  Roboto Mono variable (wght 100-700), Apache License 2.0, see Roboto-Mono-Apache-2.0.txt

These are the WOFF2 subsets (latin, latin-ext) that Google Fonts serves, unmodified, so card metrics match the
design prototype. A host's default CSP blocks data: fonts, so the card resource lists this server's origin in
_meta.ui.csp.resourceDomains and loads them from here.
