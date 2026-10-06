"""
The six option-list routes, through Flask's test client.

The migration proved the *table* is right; this proves the *endpoints* are, because that is what
the registration screen and the Android app actually call:

  - a centre sees the shared rows plus its own, never another centre's
  - a registerer cannot write at all
  - a centre admin may add to and edit their own list, but NOT a shared row, and is told why
  - none of that can damage the shared list

Every request carries a CF-Connecting-IP header. Without it the Flask test client looks like
127.0.0.1, which is_host_device() treats as the host machine — every check would pass for the
wrong reason. Tunnel traffic sets that header, which is precisely why is_host_device() rejects it.

Run: python scripts/verify_option_routes.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import app as appmod  # noqa: E402
import jwt as pyjwt  # noqa: E402

flask_app = appmod.app
db = appmod.db

failed = 0
REMOTE = {'CF-Connecting-IP': '203.0.113.7'}


def check(name, cond, detail=""):
    global failed
    if cond:
        print("ok:", name)
    else:
        print("FAIL:", name, detail)
        failed += 1


def bearer(username, role):
    secret = appmod._get_jwt_secret()
    if not secret:
        print("JWT_SHARED_SECRET is not set — cannot exercise the API auth path")
        sys.exit(1)
    token = pyjwt.encode(
        {'sub': username, 'role': role, 'iat': int(time.time()), 'exp': int(time.time()) + 600},
        secret, algorithm='HS256')
    return {'Authorization': f'Bearer {token}', **REMOTE}


def main():
    with flask_app.app_context():
        centres = [c.centre_id for c in appmod.Centre.query.limit(2).all()]
        if len(centres) < 2:
            print("need two centres"); sys.exit(1)
        a, b = centres[0], centres[1]

        probe = "__route_probe_A__"
        appmod.RemarkOption.query.filter(appmod.RemarkOption.text.like("\\_\\_%", escape="\\")).delete(
            synchronize_session=False)
        db.session.add(appmod.RemarkOption(text=probe, centre_id=a))
        db.session.commit()
        shared_row = appmod.RemarkOption.query.filter_by(centre_id=None).order_by(
            appmod.RemarkOption.id).first()
        shared_id, shared_text = shared_row.id, shared_row.text
        shared_count = appmod.RemarkOption.query.filter_by(centre_id=None).count()

        # A registerer needs real access to its centre, so a 403 proves the ROLE gate rejected it
        # rather than the centre gate.
        for uname, role in (('__probe_reg__', 'registerer'), ('__probe_admin__', 'centre_admin')):
            appmod.CentreAccess.query.filter_by(username=uname).delete()
            db.session.add(appmod.CentreAccess(
                username=uname, centre_id=a, role=role, status='active'))
        db.session.commit()

    client = flask_app.test_client()
    admin = bearer('__probe_admin__', 'centre_admin')
    reg = bearer('__probe_reg__', 'registerer')
    reg_b = bearer('__probe_reg_b__', 'registerer')

    # 0 — is_host_device must be false for these requests, or nothing below means anything.
    with flask_app.test_request_context('/api/remarks', headers=REMOTE):
        check("test requests are not treated as the host device", not appmod.is_host_device())

    # 1 — scoping.
    ra = client.get(f'/api/remarks?centre_id={a}', headers=reg)
    rb = client.get(f'/api/remarks?centre_id={b}', headers=admin)  # admin is cross-centre-readable
    check("centre A GET ok", ra.status_code == 200, ra.data[:200])
    check("centre B GET ok", rb.status_code == 200, rb.data[:200])
    if ra.status_code == 200 and rb.status_code == 200:
        ta = [x['text'] for x in ra.get_json()]
        tb = [x['text'] for x in rb.get_json()]
        check("centre A sees its private row", probe in ta, ta)
        check("centre B does not see it", probe not in tb, tb)
        check("both still see every shared row", len(tb) >= shared_count)
        check("is_shared agrees with centre_id",
              all(x['is_shared'] == (x['centre_id'] is None) for x in ra.get_json()))
        check("shared rows sort before the centre's own", ta.index(probe) == len(ta) - 1, ta)

    # 2 — a registerer of ANOTHER centre cannot read centre A's list.
    cross_read = client.get(f'/api/remarks?centre_id={a}', headers=reg_b)
    check("a registerer with no access to A is refused A's list", cross_read.status_code == 403,
          cross_read.status_code)

    # 3 — a registerer may not write.
    w = client.post('/api/remarks', json={'text': '__nope__', 'centre_id': a}, headers=reg)
    check("registerer cannot add a remark", w.status_code == 403, w.data[:200])

    # 4 — a centre admin owns their own list.
    added = client.post('/api/remarks', json={'text': '__admin_added__', 'centre_id': a}, headers=admin)
    check("centre admin can add to their own list", added.status_code == 201, added.data[:200])
    new_id = None
    if added.status_code == 201:
        body = added.get_json()
        new_id = body['id']
        check("the new row is scoped to their centre", body['centre_id'] == a, body)

        renamed = client.put(f'/api/remarks/{new_id}',
                             json={'text': '__admin_renamed__', 'centre_id': a}, headers=admin)
        check("centre admin can rename their own row", renamed.status_code == 200, renamed.data[:200])

    # 5 — but not a shared row.
    blocked = client.put(f'/api/remarks/{shared_id}', json={'text': '__hijack__', 'centre_id': a},
                         headers=admin)
    check("centre admin cannot edit a shared row", blocked.status_code == 403, blocked.status_code)
    if blocked.status_code == 403:
        msg = (blocked.get_json() or {}).get('error', '')
        check("and is told to add their own instead", 'add your own' in msg.lower(), msg)

    blocked_del = client.delete(f'/api/remarks/{shared_id}', json={'centre_id': a}, headers=admin)
    check("centre admin cannot delete a shared row", blocked_del.status_code == 403,
          blocked_del.status_code)

    # 6 — nor another centre's row.
    with flask_app.app_context():
        db.session.add(appmod.RemarkOption(text='__b_row__', centre_id=b))
        db.session.commit()
        b_id = appmod.RemarkOption.query.filter_by(text='__b_row__').first().id
    cross = client.put(f'/api/remarks/{b_id}', json={'text': '__stolen__', 'centre_id': a},
                       headers=admin)
    check("centre admin cannot edit another centre's row", cross.status_code == 403, cross.status_code)

    if new_id:
        gone = client.delete(f'/api/remarks/{new_id}', json={'centre_id': a}, headers=admin)
        check("centre admin can delete their own row", gone.status_code == 200, gone.status_code)

    # 7 — addresses follow the same rules (one spot check, the code path is shared).
    addr = client.post('/api/addresses', json={'text': '__admin_addr__', 'centre_id': a}, headers=admin)
    check("centre admin can add an address for their centre", addr.status_code == 201, addr.data[:200])
    if addr.status_code == 201:
        client.delete(f"/api/addresses/{addr.get_json()['id']}", json={'centre_id': a}, headers=admin)

    # 8 — the shared list came through untouched.
    with flask_app.app_context():
        still = appmod.RemarkOption.query.filter_by(centre_id=None).count()
        check(f"no shared row was harmed ({still} of {shared_count})", still == shared_count)
        check("the shared row still has its original text",
              appmod.RemarkOption.query.get(shared_id).text == shared_text)

        appmod.RemarkOption.query.filter(appmod.RemarkOption.text.like("\\_\\_%", escape="\\")).delete(
            synchronize_session=False)
        appmod.AddressOption.query.filter(appmod.AddressOption.text.like("\\_\\_%", escape="\\")).delete(
            synchronize_session=False)
        appmod.CentreAccess.query.filter(
            appmod.CentreAccess.username.in_(['__probe_reg__', '__probe_admin__'])).delete(
            synchronize_session=False)
        db.session.commit()

    print("\nALL PASS" if failed == 0 else f"\n{failed} FAILED")
    sys.exit(0 if failed == 0 else 1)


if __name__ == '__main__':
    main()
