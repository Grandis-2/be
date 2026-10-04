#!/usr/bin/env python3
"""부하 시험용 액세스 토큰. member 와 같은 모양(RS256 · typ=at+jwt · kid · iss/aud/sub/sid/role/type/jti/iat/exp)으로 만든다.

키 한 쌍을 새로 만들어 공개키 PEM 을 쓰고(대기열은 jwt.public-keys 로 검증), 회원 토큰 CSV(customerId,accessToken)와
관리자 토큰 한 줄을 쓴다. 실행: python3 make-tokens.py --count 5000 --out build/
"""
import argparse
import pathlib
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

KID = "load-test"


def token(key, subject, role, now, ttl):
    claims = {
        "iss": "nova", "aud": "nova-api", "sub": subject, "sid": str(uuid.uuid4()), "role": role,
        "type": "ACCESS", "jti": str(uuid.uuid4()), "iat": now, "exp": now + ttl,
    }
    return jwt.encode(claims, key, algorithm="RS256", headers={"kid": KID, "typ": "at+jwt"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=5000)
    parser.add_argument("--first-customer", type=int, default=1_000_000)
    parser.add_argument("--ttl-seconds", type=int, default=4 * 3600)
    parser.add_argument("--out", default="build")
    args = parser.parse_args()

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    (out / "public-key.pem").write_bytes(key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    now = int(time.time())
    with open(out / "tokens.csv", "w") as tokens:
        for i in range(args.count):
            customer = args.first_customer + i
            tokens.write(f"{customer},{token(key, str(customer), 'USER', now, args.ttl_seconds)}\n")
    (out / "admin-token.txt").write_text(token(key, "admin", "ADMIN", now, args.ttl_seconds))
    print(f"kid={KID} 회원 토큰 {args.count}개 → {out}/tokens.csv, 공개키 → {out}/public-key.pem")


if __name__ == "__main__":
    main()
