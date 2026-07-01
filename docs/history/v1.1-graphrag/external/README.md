# External Reference Materials

This directory stores downloaded reference materials for the v1.1 Graph-RAG planning work.

## Files

- `imo-colreg-2016-supplement.pdf`
  - Source: <https://wwwcdn.imo.org/localresources/en/publications/Documents/Supplements/English/QB904E_012016.pdf>
  - Scope: IMO public supplement for COLREG amendments. This is not the full consolidated COLREG text.
- `govinfo-cfr-2025-title33-chapI-subchapE.md`
  - Source PDF: <https://www.govinfo.gov/content/pkg/CFR-2025-title33-vol1/pdf/CFR-2025-title33-vol1-chapI-subchapE.pdf>
  - Retrieved via public reader proxy because direct TLS access to `www.govinfo.gov` failed locally.
  - Scope: 33 CFR Chapter I, Subchapter E, including Part 83 Navigation Rules.
- `cornell-33-cfr-part83-subpart-b.html`
  - Source: <https://www.law.cornell.edu/cfr/text/33/part-83/subpart-B>
  - Scope: readable mirror for 33 CFR Part 83 Subpart B, Rules 4-19.
- `ecfrio-title33-part83.html`
  - Source: <https://ecfr.io/Title-33/Part-83>
  - Scope: eCFR mirror for Part 83.

## Notes

- The authoritative COLREG source remains IMO. The full consolidated IMO text may require official publication access and should not be vendored unless licensing is confirmed.
- The CFR materials are useful as public legal-reference mirrors for drafting `summary`, `principle`, and `sourceCitation` fields, not as a replacement for IMO citation where the project intends to cite `COLREGS 1972`.
