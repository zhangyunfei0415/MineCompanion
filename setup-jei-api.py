"""Fetch the pinned compile-only public JEI API; never install a runtime mod or read credentials."""
from pathlib import Path
import urllib.request,hashlib,sys
folder=Path(__file__).resolve().parent/'.build-deps';folder.mkdir(exist_ok=True)
path=folder/'jei-1.20.1-common-api-15.56.0.205.jar'
expected='82849064411c036d4067890dad48a58a09c03deb2ce68c2fc72faf4eb130ba83'
if not path.exists():
    assert '--download' in sys.argv,'Run python setup-jei-api.py --download once before the offline build'
    url='https://maven.blamejared.com/mezz/jei/jei-1.20.1-common-api/15.56.0.205/'+path.name
    with urllib.request.urlopen(url,timeout=60) as r:data=r.read()
    assert hashlib.sha256(data).hexdigest()==expected,'Downloaded API hash mismatch'
    path.write_bytes(data)
assert hashlib.sha256(path.read_bytes()).hexdigest()==expected,'Cached API hash mismatch'
print('Pinned JEI API verified. Compile-only; not included in the companion JAR.')
