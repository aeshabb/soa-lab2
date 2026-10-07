#!/usr/bin/env python3
"""Настроить таблицы PostgreSQL и перенести прежнее JSON/JSONB-хранилище.

Запускать при остановленном Ticket Service. Пароль остаётся в ~/.pgpass.
"""
import getpass
from datetime import datetime
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
from urllib.parse import quote

lab = Path(sys.argv[1])
host = os.environ.get('PGHOST', 'pg')
port = os.environ.get('PGPORT', '5432')
database = os.environ.get('PGDATABASE', 'studs')
user = os.environ.get('PGUSER', getpass.getuser())
command = ['psql', '-X', '-w', '-h', host, '-p', port, '-d', database,
           '-U', user, '-v', 'ON_ERROR_STOP=1', '-At']


def sql(text):
    return subprocess.run(command, input=text, text=True, check=True,
                          stdout=subprocess.PIPE).stdout.strip()


def copy_value(value):
    if value is None:
        return r'\N'
    return str(value).replace('\\', '\\\\').replace('\t', r'\t').replace('\n', r'\n').replace('\r', r'\r')


def import_tickets(data, replace_jsonb=False):
    statements = ['BEGIN;']
    if replace_jsonb:
        statements += ['DROP TABLE soa_lab2_tickets;', definition]
    statements += ['COPY soa_lab2_venues (id, name, capacity, type) FROM STDIN;']
    for ticket in data['items']:
        venue = ticket['venue']
        statements.append('\t'.join(copy_value(venue.get(key)) for key in ('id', 'name', 'capacity', 'type')))
    statements += [r'\.', 'COPY soa_lab2_tickets (id, name, coordinate_x, coordinate_y, creation_date, price, comment, type, venue_id, person_id) FROM STDIN;']
    for ticket in data['items']:
        values = [ticket['id'], ticket['name'], ticket['coordinates']['x'], ticket['coordinates']['y'],
                  datetime.strptime(ticket['creationDate'], '%d.%m.%Y %H:%M:%S').strftime('%Y-%m-%d %H:%M:%S'),
                  ticket['price'], ticket.get('comment'), ticket['type'], ticket['venue']['id'], ticket.get('personId')]
        statements.append('\t'.join(copy_value(value) for value in values))
    statements += [r'\.', 'INSERT INTO soa_lab2_state VALUES (1, '
                   + str(int(data['nextTicketId'])) + ', ' + str(int(data['nextVenueId']))
                   + ') ON CONFLICT (id) DO UPDATE SET next_ticket_id = EXCLUDED.next_ticket_id, next_venue_id = EXCLUDED.next_venue_id;', 'COMMIT;']
    sql('\n'.join(statements) + '\n')
    print('Перенесено билетов в обычные столбцы PostgreSQL:', len(data['items']))


schema = sql('SELECT current_schema();')
definition = (lab / 'deploy/postgres.sql').read_text()
legacy_jsonb = sql("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'soa_lab2_tickets' AND column_name = 'payload';") == '1'
if legacy_jsonb:
    counters = sql('SELECT next_ticket_id, next_venue_id FROM soa_lab2_state WHERE id = 1;').split('|')
    data = {'items': [json.loads(line) for line in sql('SELECT payload FROM soa_lab2_tickets ORDER BY id;').splitlines()],
            'nextTicketId': int(counters[0]), 'nextVenueId': int(counters[1])}
    backup = lab / '.runtime/tickets-before-relational.json'
    backup.write_text(json.dumps(data, ensure_ascii=False, indent=2))
    backup.chmod(0o600)
    import_tickets(data, replace_jsonb=True)
else:
    sql(definition)
    if sql('SELECT count(*) FROM soa_lab2_state;') == '0':
        if sql('SELECT count(*) FROM soa_lab2_tickets;') != '0':
            raise RuntimeError('Таблица билетов уже содержит данные без счётчиков; перенос остановлен')
        source = lab / '.runtime/tickets.json'
        data = json.loads(source.read_text()) if source.exists() else {'items': [], 'nextTicketId': 1, 'nextVenueId': 1}
        if source.exists():
            shutil.copy2(source, lab / '.runtime/tickets-before-postgres.json')
        import_tickets(data)
    else:
        print('PostgreSQL уже настроен; существующие данные сохранены')

url = ('jdbc:postgresql://' + host + ':' + port + '/' + quote(database, safe='')
       + '?currentSchema=' + quote(schema, safe='') + '&connectTimeout=5&socketTimeout=15')
settings = lab / '.runtime/settings.sh'
lines = [line for line in settings.read_text().splitlines()
         if not line.startswith(('export TICKET_DATABASE_URL=',
                                 'export TICKET_DATABASE_USER=', 'export TICKET_DATA_FILE='))]
lines += ['export TICKET_DATABASE_URL=' + shlex.quote(url),
          'export TICKET_DATABASE_USER=' + shlex.quote(user)]
settings.write_text('\n'.join(lines) + '\n')
settings.chmod(0o600)
print('База:', database, 'схема:', schema, 'таблица: soa_lab2_tickets')
