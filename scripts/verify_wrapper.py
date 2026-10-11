#!/usr/bin/env python3
import hashlib
from pathlib import Path
root = Path(__file__).resolve().parents[1]
expected = 'ad16bca46cb71bb5887c546860d93992aeee35c1fdaf4a242b0a612375d937e0'
assert hashlib.sha256((root/'gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest() == expected
properties = (root/'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'distributionSha256Sum=7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24' in properties
assert 'gradle-8.14.6-bin.zip' in properties
assert 'validateDistributionUrl=true' in properties
print('Gradle wrapper JAR and distribution checksum pins verified')
