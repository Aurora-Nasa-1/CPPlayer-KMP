# Provider Contract Maintenance

The current provider-author entry point is
[the development guide](../docs/dev/PROVIDER_DEV_GUIDE.md).
This directory owns the cross-language contract and its reference generation.

| Source | Responsibility |
| --- | --- |
| schema.mjs | Contract version, ordered record fields, defaults, operation tags, groups and capability names |
| documentation.mjs | Every business operation, every record, common field meanings and record-specific overrides |
| generate.mjs | Kotlin/TS declarations, codecs, dispatch, host/source forwarding, fixtures and reference pages |
| documentation.test.mjs | Cross-language signature/doc coverage and host selector consistency |

Edit schema/metadata, then regenerate and verify:

```powershell
node provider-contract/generate.mjs
node provider-contract/generate.mjs --check
node --test provider-contract/documentation.test.mjs provider-sdk-kmp/js/test.mjs
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
```

`--check` fails on missing/stale documentation metadata or changed generated
output. All 160 business methods and 154 DTOs currently have documentation.
The generator maintains SDK method KDoc in MusicProvider.kt/CatalogProvider.kt
while preserving their handwritten implementation/default bodies. Runtime and
lifecycle declarations remain handwritten.

Do not change existing field order, types or operation tags when adding a
capability. A record's binary serialization is positional. New optional business
methods must retain an Unsupported default; altering an existing wire layout
requires an explicit compatibility/version decision.

Generated reference pages are
[SDK_OPERATIONS.md](../docs/dev/SDK_OPERATIONS.md) and
[SDK_TYPES.md](../docs/dev/SDK_TYPES.md). Domain workflows live in
[PROVIDER_WORKFLOWS.md](../docs/dev/PROVIDER_WORKFLOWS.md).
Check a new constraint against ProviderHost/ExtendedOperationValidation and
runtime implementation before documenting it as enforced. Describe provider
responsibilities separately from host checks.

Run the repository's required desktop compilation after declaration changes.
The verification scripts include the documentation tests and retain JUnit XML;
the docs tests do not replace upstream/provider integration tests.
