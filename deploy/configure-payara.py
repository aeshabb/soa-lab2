#!/usr/bin/env python3
"""Configure a fresh Payara domain before starting its HTTPS-only application."""
import sys
import xml.etree.ElementTree as ET

tree = ET.parse(sys.argv[1])
config = tree.getroot().find("./configs/config[@name='server-config']")
listeners = config.find('./network-config/network-listeners')
for listener in list(listeners):
    name = listener.get('name')
    if name == 'http-listener-1':
        listeners.remove(listener)
    else:
        listener.set('port', sys.argv[2] if name == 'http-listener-2' else sys.argv[3])
        listener.set('address', '0.0.0.0' if name == 'http-listener-2' else '127.0.0.1')
config.find("./http-service/virtual-server[@id='server']").set('network-listeners', 'http-listener-2')
https = config.find("./network-config/protocols/protocol[@name='http-listener-2']")
https.set('security-enabled', 'true')
https.find('ssl').set('cert-nickname', 'soa-payara')
https.find('ssl').set('tls-enabled', 'true')
https.find('ssl').set('ssl3-enabled', 'false')
for listener in config.findall('./iiop-service/iiop-listener') + config.findall('./admin-service/jmx-connector'):
    listener.set('enabled', 'false')
    listener.set('address', '127.0.0.1')
config.find('hazelcast-config-specific-configuration').set('enabled', 'false')
# Приложение не использует JMS: не запускаем встроенный брокер на общем порту 7676.
config.find('jms-service').set('type', 'REMOTE')
java = config.find('java-config')
for option in java.findall('jvm-options'):
    if (option.text or '').startswith('-Xmx'):
        option.text = '-Xmx384m'
    if (option.text or '').startswith('-Dosgi.shell.telnet.port='):
        option.text = '-Dosgi.shell.telnet.port=-1'
ET.SubElement(java, 'jvm-options').text = '-XX:ActiveProcessorCount=2'
tree.write(sys.argv[1], encoding='UTF-8', xml_declaration=True)
