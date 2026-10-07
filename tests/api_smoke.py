#!/usr/bin/env python3
"""End-to-end tests against the real WildFly/Payara deployment; removes only its own tickets."""
import argparse
import copy
import json
import ssl
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

p = argparse.ArgumentParser()
p.add_argument('--ticket-url', required=True)
p.add_argument('--booking-url', required=True)
p.add_argument('--ticket-cert', required=True)
p.add_argument('--booking-cert', required=True)
args = p.parse_args()
ticket_context = ssl.create_default_context(cafile=args.ticket_cert)
booking_context = ssl.create_default_context(cafile=args.booking_cert)
owned = set()
checks = 0
prefix = 'lab2-test-' + uuid.uuid4().hex


def request(path, method='GET', body=None, expected=200, booking=False, content_type='application/json', raw=None):
    global checks
    base = args.booking_url if booking else args.ticket_url
    context = booking_context if booking else ticket_context
    data = raw if raw is not None else json.dumps(body).encode() if body is not None else None
    headers = {'Accept': 'application/json'}
    if data is not None and content_type is not None:
        headers['Content-Type'] = content_type
    # urllib otherwise adds application/x-www-form-urlencoded: deliberately invalid as well.
    req = Request(base.rstrip('/') + path, data=data, method=method, headers=headers)
    try:
        response = urlopen(req, context=context, timeout=30)
    except HTTPError as e:
        response = e
    status = response.code
    text = response.read().decode()
    result = json.loads(text) if text else None
    assert status == expected, (method, path, status, expected, result)
    if status >= 400:
        assert result['status'] == status and all(k in result for k in ['timestamp', 'error', 'message', 'path'])
    checks += 1
    return result, response.headers


def create(**changes):
    data = {'name': prefix, 'coordinates': {'x': 15, 'y': -42.75}, 'price': 1500.5,
            'comment': 'front', 'type': 'USUAL', 'venue': {'name': 'Arena', 'capacity': 12000, 'type': 'CINEMA'}, 'personId': None}
    data.update(changes)
    result, headers = request('/tickets', 'POST', data, 201)
    owned.add(result['id'])
    assert headers['Location'].endswith('/tickets/' + str(result['id']))
    return result


def as_input(t):
    data = copy.deepcopy(t)
    del data['id'], data['creationDate'], data['venue']['id']
    return data


def unused_price():
    # Удаление «любого» билета по цене не должно затрагивать чужую коллекцию.
    while True:
        price = 1_000_000 + uuid.uuid4().int % 8_000_000
        if request('/tickets?' + urlencode({'price': price}))[0]['totalElements'] == 0:
            return price


try:
    while True:
        person = 1 + uuid.uuid4().int % 2_147_483_646
        if request('/tickets?' + urlencode({'personId': person}))[0]['totalElements'] == 0:
            break
    prices = set()
    while len(prices) < 5:
        prices.add(unused_price())
    cheap_price, middle_price, original_price, reserved_price, absent_price = sorted(prices)
    original = create(price=original_price)
    id_ = original['id']
    assert request(f'/tickets/{id_}')[0] == original
    all_fields = {'id': id_, 'name': original['name'], 'coordinateX': 15, 'coordinateY': -42.75,
                  'creationDate': original['creationDate'], 'price': original_price, 'comment': 'front', 'type': 'USUAL',
                  'venueId': original['venue']['id'], 'venueName': 'Arena', 'venueCapacity': 12000, 'venueType': 'CINEMA'}
    page = request('/tickets?' + urlencode(all_fields))[0]
    assert [t['id'] for t in page['items']] == [id_]
    for query in [dict(nameContains=prefix.upper(), priceFrom=original_price, priceTo=original_price),
                  dict(name=prefix, venueNameContains='aRE', commentContains='FRONT', venueCapacityFrom=12000, venueCapacityTo=12000),
                  dict(name=prefix, creationDateFrom=original['creationDate'], creationDateTo=original['creationDate']),
                  dict(name=prefix, coordinateXFrom=15, coordinateYTo=-42.75)]:
        assert request('/tickets?' + urlencode(query))[0]['totalElements'] == 1
    cheaper = create(name=prefix + '-cheap', price=cheap_price, comment=None,
                     coordinates={'x': -1, 'y': 5}, type='CHEAP',
                     venue={'name': 'A', 'capacity': 10, 'type': 'BAR'})
    more = create(name=prefix + '-other', price=middle_price, comment='',
                  coordinates={'x': 20, 'y': -5}, type='BUDGETARY',
                  venue={'name': 'Z', 'capacity': 20, 'type': 'MALL'})
    fields = {**{field: (field,) for field in ['id', 'name', 'creationDate', 'price', 'comment', 'type', 'personId']},
              'coordinateX': ('coordinates', 'x'), 'coordinateY': ('coordinates', 'y'),
              'venueId': ('venue', 'id'), 'venueName': ('venue', 'name'),
              'venueCapacity': ('venue', 'capacity'), 'venueType': ('venue', 'type')}
    for field, path in fields.items():
        for descending in [False, True]:
            items = request('/tickets?' + urlencode({'sort': ('-' if descending else '') + field + ',-id', 'nameContains': prefix}))[0]['items']
            assert {t['id'] for t in items} == {id_, cheaper['id'], more['id']}
            values = []
            for item in items:
                value = item
                for part in path:
                    value = value[part]
                if value is not None:
                    values.append(value)
            assert values == sorted(values, reverse=descending), (field, descending, values)
    # Равные даты проверяют применение второго поля сортировки.
    tied = request('/tickets?' + urlencode({'nameContains': prefix, 'sort': 'creationDate,-id'}))[0]['items']
    for left, right in zip(tied, tied[1:]):
        if left['creationDate'] == right['creationDate']:
            assert left['id'] > right['id']
    selected = request('/tickets?' + urlencode({'nameContains': prefix, 'sort': '-price,name', 'size': 1, 'page': 2}))[0]
    assert selected['items'][0]['id'] == more['id'] and selected['totalElements'] == 3 and selected['totalPages'] == 3
    assert request('/tickets?' + urlencode({'nameContains': prefix, 'page': 1000000}))[0]['items'] == []
    updated_input = as_input(original)
    updated_input.update(name=prefix + '-updated', price=reserved_price, personId=person)
    updated = request(f'/tickets/{id_}', 'PUT', updated_input)[0]
    assert updated['id'] == id_ and updated['creationDate'] == original['creationDate'] and updated['venue']['id'] == original['venue']['id']
    request(f'/tickets/{id_}', 'DELETE', expected=409)
    request(f'/tickets/price/{reserved_price}', 'DELETE', expected=409)
    vip, headers = request(f'/booking/sell/vip/{id_}/{person}', 'POST', expected=201, booking=True)
    owned.add(vip['id'])
    assert vip['type'] == 'VIP' and vip['price'] == reserved_price * 2 and vip['personId'] == person
    location = urlsplit(headers['Location'])
    assert location.scheme == 'https' and location.path == f"/tickets/{vip['id']}"
    assert location.netloc != urlsplit(args.booking_url).netloc
    # URL первого сервиса внутри SSH-туннеля может отличаться номером локального порта.
    assert request(location.path)[0] == vip
    assert vip['venue']['id'] != updated['venue']['id'] and vip['id'] != id_
    assert request(f'/tickets/{id_}')[0] == updated
    average = request('/tickets/price/average')[0]
    assert average['count'] >= 4 and average['average'] > 0
    assert request('/tickets/comment/min')[0]['comment'] == ''
    deleted = request(f'/tickets/price/{cheap_price}', 'DELETE')[0]
    assert deleted['id'] == cheaper['id']; owned.remove(cheaper['id'])
    request(f"/tickets/{cheaper['id']}", expected=404)
    request(f'/tickets/price/{absent_price}', 'DELETE', expected=404)
    request('/tickets/0', expected=400)
    request('/tickets/not-a-number', expected=400)
    request('/booking/sell/vip/1/abc', 'POST', expected=400, booking=True)
    request('/booking/person/0/cancel', 'POST', expected=400, booking=True)
    request('/booking/sell/vip/2147483647/7', 'POST', expected=404, booking=True)
    for query in ['sort=prise', 'unknown=1', 'size=101', 'page=0', 'price=-1', 'type=WRONG',
                  'creationDate=31.02.2026%2000:00:00', 'id=2147483648', 'coordinateX=1.5', 'name=', 'id=1&id=2']:
        request('/tickets?' + query, expected=400)
    for query in ['price=1f', 'coordinateY=0x1p0']:
        request('/tickets?' + query, expected=400)
    request('/tickets', 'POST', raw=b'{broken', expected=400)
    request('/tickets', 'POST', raw=b'[]', expected=400)
    for extra in [b' {}', b' trailing', b']']:
        request(f'/tickets/{id_}', 'PUT', raw=json.dumps(updated_input).encode() + extra, expected=400)
    assert request(f'/tickets/{id_}')[0] == updated
    assert request(f'/tickets/{id_}', 'PUT', raw=json.dumps(updated_input).encode() + b' \n\t')[0] == updated
    for method in ['GET', 'PUT', 'DELETE']:
        request('/client-api/tickets/not%20an%20id', method, body=updated_input if method == 'PUT' else None,
                expected=400, booking=True)
    request('/client-api/tickets/price%3Fid%3D1', expected=400, booking=True)
    request('/client-api/tickets/price%23average', expected=400, booking=True)
    request('/tickets', 'POST', body={}, content_type='text/plain', expected=415)
    request('/tickets', 'POST', body={}, content_type=None, expected=415)
    invalid = request('/tickets', 'POST', body={}, expected=422)[0]
    assert invalid['violations']
    for changes in [dict(price=0), dict(price=1e40), dict(price=1e-60), dict(name=''), dict(type=None),
                    dict(personId=-1), dict(coordinates={'x': 2147483648, 'y': 0}),
                    dict(venue={'name': 'x', 'capacity': 0}), dict(id=7), dict(creationDate='01.01.2000 00:00:00')]:
        bad = as_input(original); bad.update(changes)
        request('/tickets', 'POST', body=bad, expected=422)
    large = create(price=2e38)
    request(f"/booking/sell/vip/{large['id']}/7", 'POST', expected=422, booking=True)
    # More than 100 reservations proves that cancellation does not skip subsequent pages.
    for i in range(101):
        create(name=prefix + f'-reservation-{i}', personId=person)
    cancelled = request(f'/booking/person/{person}/cancel', 'POST', booking=True)[0]
    assert cancelled['cancelledCount'] == 103 and len(set(cancelled['cancelledTicketIds'])) == 103
    assert request('/tickets?' + urlencode({'personId': person}))[0]['totalElements'] == 0
    assert request(f'/booking/person/{person}/cancel', 'POST', booking=True)[0]['cancelledCount'] == 0
    assert request('/client-api/tickets?' + urlencode({'nameContains': prefix}), booking=True)[0]['totalElements'] == len(owned)
    print(f'PASS: {checks} HTTP checks; CRUD, all fields, filtering, sorting, paging, extra operations, VIP, 103 cancellations and error responses.')
finally:
    for id_ in sorted(owned):
        ticket = request(f'/tickets/{id_}')[0]
        if ticket.get('personId') is not None:
            data = as_input(ticket); data['personId'] = None
            request(f'/tickets/{id_}', 'PUT', data)
        request(f'/tickets/{id_}', 'DELETE', expected=204)
    print('Test tickets removed.')
