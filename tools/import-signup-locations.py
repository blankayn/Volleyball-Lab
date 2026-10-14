"""Build offline picker assets from public world and PSGC source snapshots.

World database: https://github.com/dr5hn/countries-states-cities-database (ODbL)
Philippines: https://github.com/jeffreybernadas/psgc-api (MIT)
Download their JSON snapshots into .codex-tmp/signup-location before running.
The generated world database adaptation is also distributed under ODbL.
"""
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / ".codex-tmp/signup-location"
TARGET = ROOT / "app/src/main/assets/locations"


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def option(identifier, name):
    return {"id": str(identifier), "name": name.strip()}


def by_name(values):
    return sorted(values, key=lambda x: (x["name"].casefold(), x["id"]))


world = json.loads((SOURCE / "world.json").read_text(encoding="utf-8"))
save(TARGET / "countries.json", by_name([option(x["iso2"], x["name"]) for x in world]))
for country in world:
    if country["iso2"] == "PH":
        continue
    areas = []
    for state in country["states"]:
        cities = by_name([option(x["id"], x["name"]) for x in state["cities"]])
        areas.append({**option(state["id"], state["name"]), "cities": cities})
    save(TARGET / "countries" / f'{country["iso2"]}.json', by_name(areas))

ph = json.loads((SOURCE / "psgc.json").read_text(encoding="utf-8"))
provinces = {x["code"]: x for x in ph if x["geographicLevel"] == "Prov"}
regions = {x["psgc10DigitCode"]: x for x in ph if x["geographicLevel"] == "Reg"}
cities = {x["psgc10DigitCode"]: x for x in ph if x["geographicLevel"] in ("City", "Mun")}
legacy_cities = {x["code"]: x for x in cities.values()}
area_cities = defaultdict(list)
area_names = {}
for city in cities.values():
    province = provinces.get(city["code"][:4] + "00000")
    if province:
        area_id, area_name = province["psgc10DigitCode"], province["name"]
    else:
        region_id = city["psgc10DigitCode"][:2] + "00000000"
        region = regions[region_id]
        area_id = region_id
        area_name = "Metro Manila (NCR)" if region_id.startswith("13") else region["name"] + " · independent cities"
    area_names[area_id] = area_name
    area_cities[area_id].append(option(city["psgc10DigitCode"], city["name"]))
areas = [{**option(key, area_names[key]), "cities": by_name(values)} for key, values in area_cities.items()]
save(TARGET / "countries/PH.json", by_name(areas))

barangays = defaultdict(list)
orphans = []
for entry in ph:
    if entry["geographicLevel"] != "Brgy":
        continue
    code = entry["psgc10DigitCode"]
    city = cities.get(code[:7] + "000") or cities.get(code[:5] + "00000")
    if not city:
        city = legacy_cities.get(entry["code"][:6] + "000")
    if not city:
        orphans.append(option(code, entry["name"]))
        continue
    barangays[city["psgc10DigitCode"]].append(option(code, entry["name"]))
if orphans:
    raise ValueError(f"Barangays without a valid city parent: {orphans[:10]}")
for code in cities:
    save(TARGET / "barangays" / f"{code}.json", by_name(barangays[code]))
assert sum(map(len, barangays.values())) == sum(x["geographicLevel"] == "Brgy" for x in ph)
assert len({x["iso2"] for x in world}) == len(world)
print(f"Imported {len(world)} countries; PH has {len(areas)} province/area options, {len(cities)} cities/municipalities, and {sum(map(len, barangays.values()))} barangays.")
