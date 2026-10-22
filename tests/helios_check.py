#!/usr/bin/env python3
"""Run on Helios: verify PostgreSQL fields, persistence and both service outages.

Temporarily stops this lab's servers; restores both and removes only its own data.
Credentials are read by pgJDBC/psql from ~/.pgpass and never printed.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import signal
import ssl
import subprocess
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--lab-home', type=Path, default=Path.home() / 'soa-lab2')
parser.add_argument('--signal', choices=['TERM', 'KILL'], default='TERM',
                    help='TERM matches kill PID; KILL matches kill -9 PID')
args = parser.parse_args()
lab = args.lab_home.absolute()
environment = subprocess.check_output(
    ['bash', '-c', 'source "$1"; env -0', 'bash', str(lab / '.runtime/settings.sh')])
env = dict(item.decode().split('=', 1) for item in environment.split(b'\0') if b'=' in item)
env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
env['JAVA_TOOL_OPTIONS'] = '-Xmx128m -XX:ActiveProcessorCount=2'
env['JAVA_OPTS'] = '-Xms64m -Xmx384m -XX:ActiveProcessorCount=2 -Djava.net.preferIPv4Stack=true'
bases = {name: 'https://localhost:' + env[key] for name, key in
         [('ticket', 'TICKET_HTTPS_PORT'), ('booking', 'BOOKING_HTTPS_PORT')]}
contexts = {name: ssl.create_default_context(cafile=str(lab / '.runtime' / cert))
            for name, cert in [('ticket', 'wildfly.crt'), ('booking', 'payara.crt')]}
db = urlsplit(env['TICKET_DATABASE_URL'].removeprefix('jdbc:'))
schema = parse_qs(db.query)['currentSchema'][0]
assert re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*', schema)
db_env = {**env, 'PGOPTIONS': '-c search_path=' + schema}
psql = ['psql', '-X', '-w', '-h', db.hostname, '-p', str(db.port or 5432),
        '-d', db.path.lstrip('/'), '-U', env['TICKET_DATABASE_USER'], '-At', '-v', 'ON_ERROR_STOP=1']
asadmin = [str(lab / 'runtime/payara6/bin/asadmin'), '--port', env['PAYARA_ADMIN_PORT']]
owned = set()
checks = 0


def sql(query):
    return subprocess.check_output(psql + ['-c', query], env=db_env, text=True).strip()


def rows():
    query = """SELECT json_build_object(
      'id', t.id, 'name', t.name,
      'coordinates', json_build_object('x', coordinate_x, 'y', coordinate_y),
      'creationDate', to_char(creation_date, 'DD.MM.YYYY HH24:MI:SS'),
      'price', price::double precision, 'comment', comment, 'type', t.type,
      'venue', json_build_object('id', v.id, 'name', v.name, 'capacity', v.capacity, 'type', v.type),
      'personId', person_id)
      FROM soa_lab2_tickets t JOIN soa_lab2_venues v ON v.id=t.venue_id ORDER BY t.id"""
    return {item['id']: item for item in map(json.loads, sql(query).splitlines())}


def request(service, path, method='GET', body=None, expected=200):
    global checks
    data = None if body is None else json.dumps(body).encode()
    req = Request(bases[service] + path, data=data, method=method,
                  headers={'Accept': 'application/json', 'Content-Type': 'application/json'})
    try:
        response = urlopen(req, context=contexts[service], timeout=25)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read().decode()
        result = json.loads(payload) if payload else None
        assert response.code == expected, (service, path, response.code, result)
        assert response.headers.get('Cache-Control') == 'no-store', (service, path, 'cacheable response')
        if expected >= 400:
            assert result['status'] == expected and result['message'] and result['path'] == path
        checks += 1
        return result


def reachable(service):
    try:
        with urlopen(bases[service] + ('/tickets' if service == 'ticket' else '/'),
                     context=contexts[service], timeout=2):
            return True
    except HTTPError:
        return True  # A running but unhealthy server must not be started a second time.
    except (URLError, TimeoutError, OSError):
        return False


def wait(service, up):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if reachable(service) == up:
            return
        time.sleep(1)
    raise AssertionError(f'{service}: did not become {"available" if up else "unavailable"}')


def stop(service):
    runtime = str(lab / 'runtime' / ('payara6' if service == 'booking' else 'wildfly-35.0.1.Final'))
    processes = subprocess.check_output(['ps', '-ww', '-U', env['USER'], '-o', 'pid=', '-o', 'args='], text=True)
    matches = []
    for line in processes.splitlines():
        fields = line.split(None, 2)
        if len(fields) == 3 and Path(fields[1]).name == 'java' and runtime in fields[2]:
            matches.append(int(fields[0]))
    assert len(matches) == 1, (service, 'expected exactly one JVM', matches)
    pid = matches[0]
    os.kill(pid, getattr(signal, 'SIG' + args.signal))
    wait(service, False)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(1)
    raise AssertionError(f'{service}: JVM {pid} did not exit')


def start(service):
    if not reachable(service):
        if service == 'booking':
            subprocess.run(asadmin + ['start-domain', 'domain1'], env=env, check=True,
                           stdout=subprocess.DEVNULL)
        else:
            with (lab / '.runtime/wildfly.log').open('ab') as log:
                subprocess.Popen([str(lab / 'runtime/wildfly-35.0.1.Final/bin/standalone.sh'),
                                  '-b', '0.0.0.0', '-bmanagement', '127.0.0.1'],
                                 env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                 start_new_session=True)
    wait(service, True)
    # Payara opens HTTPS and serves its welcome page before JAX-RS is deployed.
    # Verify the actual application, including when a listening JVM already exists.
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            if service == 'ticket':
                request('ticket', '/tickets')
            else:
                request('booking', '/booking/person/0/cancel', 'POST', expected=400)
            return
        except (AssertionError, ValueError, URLError, TimeoutError):
            time.sleep(1)
    raise AssertionError(f'{service}: application did not become ready')


def disconnected(service, path, method='GET'):
    global checks
    try:
        request(service, path, method)
    except (URLError, TimeoutError, OSError):
        checks += 1
        return
    raise AssertionError(f'{service} returned an HTTP response while stopped')


def as_input(ticket):
    result = copy.deepcopy(ticket)
    del result['id'], result['creationDate'], result['venue']['id']
    return result


def match(ticket):
    global checks
    assert rows()[ticket['id']] == ticket, ('API/SQL mismatch', ticket['id'])
    assert request('ticket', '/tickets/' + str(ticket['id'])) == ticket
    checks += 1


baseline = rows()
prefix = 'helios-check-' + uuid.uuid4().hex
sample = {'name': prefix + ' Кириллица', 'coordinates': {'x': -2147483648, 'y': -42.125},
          'price': 123.25, 'comment': "Кавычка ' и перевод\nстроки", 'type': 'BUDGETARY',
          'venue': {'name': 'Зал ' + prefix, 'capacity': 2147483647, 'type': 'CINEMA'}, 'personId': None}
try:
    ticket = request('ticket', '/tickets', 'POST', sample, 201)
    owned.add(ticket['id'])
    match(ticket)
    updated_input = as_input(ticket)
    updated_input.update(name=prefix + '-updated', coordinates={'x': 2147483647, 'y': 0.0625},
                         price=0.125, comment=None, type='CHEAP',
                         venue={'name': prefix + '-venue', 'capacity': 7, 'type': None})
    updated = request('ticket', '/tickets/' + str(ticket['id']), 'PUT', updated_input)
    assert updated['creationDate'] == ticket['creationDate'] and updated['venue']['id'] == ticket['venue']['id']
    match(updated)
    person = 1 + uuid.uuid4().int % 2147483646
    while request('ticket', '/tickets?' + urlencode({'personId': person}))['totalElements']:
        person = 1 + uuid.uuid4().int % 2147483646
    vip_path = f"/booking/sell/vip/{ticket['id']}/{person}"
    cancel_path = f'/booking/person/{person}/cancel'
    vip = request('booking', vip_path, 'POST', expected=201)
    owned.add(vip['id'])
    assert vip['price'] == updated['price'] * 2 and vip['type'] == 'VIP' and vip['personId'] == person
    match(vip)
    cancelled = request('booking', cancel_path, 'POST')
    assert cancelled['cancelledTicketIds'] == [vip['id']]
    vip['personId'] = None
    match(vip)
    print('PASS: every ticket/venue field, SQL NULL, VIP and cancellation match PostgreSQL.', flush=True)

    stop('booking')
    disconnected('booking', vip_path, 'POST')
    disconnected('booking', cancel_path, 'POST')
    disconnected('booking', '/client-api/tickets')
    match(updated)
    independent = request('ticket', '/tickets', 'POST', sample, 201)
    owned.add(independent['id'])
    match(independent)
    start('booking')
    assert request('booking', '/client-api/tickets/' + str(ticket['id'])) == updated
    print('PASS: Payara stopped -> Booking is unreachable; Ticket API and SQL writes still work.', flush=True)

    before = rows()
    counters = sql('SELECT next_ticket_id, next_venue_id FROM soa_lab2_state WHERE id=1')
    stop('ticket')
    disconnected('ticket', '/tickets')
    request('booking', vip_path, 'POST', expected=502)
    request('booking', cancel_path, 'POST', expected=502)
    request('booking', '/client-api/tickets', expected=502)
    request('booking', '/client-api/tickets', 'POST', sample, expected=502)
    assert rows() == before
    assert sql('SELECT next_ticket_id, next_venue_id FROM soa_lab2_state WHERE id=1') == counters
    print('PASS: WildFly stopped -> both Booking operations and proxy return JSON 502; SQL is unchanged.', flush=True)
    stop('booking')
    disconnected('booking', vip_path, 'POST')
    disconnected('ticket', '/tickets')
    start('ticket')
    start('booking')
    for item in before.values():
        match(item)
    assert request('booking', '/client-api/tickets/' + str(ticket['id'])) == updated
    assert sql('SELECT count(*) FROM soa_lab2_venues v LEFT JOIN soa_lab2_tickets t ON t.venue_id=v.id WHERE t.id IS NULL') == '0'
    assert sql('SELECT next_ticket_id > (SELECT max(id) FROM soa_lab2_tickets) AND next_venue_id > (SELECT max(id) FROM soa_lab2_venues) FROM soa_lab2_state WHERE id=1') == 't'
    print(f'PASS: full restart preserves all records; {checks} API/SQL/outage checks.', flush=True)
finally:
    start('ticket')
    start('booking')
    for identifier in sorted(owned):
        item = request('ticket', '/tickets/' + str(identifier))
        if item['personId'] is not None:
            data = as_input(item)
            data['personId'] = None
            request('ticket', '/tickets/' + str(identifier), 'PUT', data)
        request('ticket', '/tickets/' + str(identifier), 'DELETE', expected=204)
        assert identifier not in rows()
        assert sql('SELECT count(*) FROM soa_lab2_venues WHERE id=' + str(item['venue']['id'])) == '0'
    assert rows() == baseline, 'Original database contents changed'
    print('CLEANUP: original records unchanged, test tickets/venues removed, both services running.', flush=True)
