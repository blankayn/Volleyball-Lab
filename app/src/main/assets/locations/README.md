# Offline address lists

Countries and non-Philippine states/cities are adapted from the
[Countries States Cities Database](https://github.com/dr5hn/countries-states-cities-database)
by dr5hn. This database adaptation is distributed under the Open Database License
(ODbL) 1.0, copied at `assets/licenses/location-world-ODbL.txt`. The compact JSON
files in this directory are the editable adaptation; there are no access restrictions.

Philippine province/area, city/municipality, and barangay lists are adapted from
[PSGC API](https://github.com/jeffreybernadas/psgc-api) by Jeffrey Bernadas, based
on Philippine Statistics Authority PSGC data. Its MIT license is copied at
`assets/licenses/location-psgc-MIT.txt`. Metro Manila is offered as a province/area
choice. Other cities use their geographic province or an independent-city area.
Manila's sub-municipal barangays are flattened under City of Manila.

Source snapshots were downloaded on 2026-10-02; this is a bundled snapshot, not a
live completeness guarantee. Cities missing from the source are not invented.
Address subdivisions are optional in sign-up. Lists are loaded offline for the
chosen country and city, and changing a parent clears dependent selections.

Rebuild with `tools/import-signup-locations.py` using the public source JSON files.
Only codes, names, and parent relationships are retained; no population or
coordinates are bundled or collected from users.
