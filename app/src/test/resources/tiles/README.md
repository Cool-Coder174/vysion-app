# Map tile fixture

`14-2620-6332.pbf.gz`: one OpenFreeMap vector tile (OpenMapTiles schema, z14, San Francisco Civic Center), gzipped as
it came over the wire (`Accept-Encoding: gzip`; 558,850 bytes unzipped, sha256 `5a397213…5ea584`), fetched
2026-10-18 from `https://tiles.openfreemap.org/planet/latest/14/2620/6332.pbf` (planet build `20261011_080001_pt`).
Read by `VectorTileTest` and `SurroundingsFinderTest`; the expected counts were cross-checked with an independent
60-line Python decoder.

© OpenMapTiles, data from OpenStreetMap (© OpenStreetMap contributors, ODbL).
