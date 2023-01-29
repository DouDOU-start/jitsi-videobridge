#!/bin/bash

INNER_IP="10.8.6.245"

DOMAIN="docker.jitsi.com"
JVB_SECRET="jvb123"

# 容器初次运行时安装deb包
if [ -d "/build" ]; then

    echo "jitsi-meet-web-config jitsi-videobridge/jvb-hostname string $DOMAIN" | debconf-set-selections
    echo "videobridge jitsi-videobridge/jvbsecret string $JVB_SECRET" | debconf-set-selections

    dpkg -i /build/jitsi-videobridge2_2.1-0-g676fb3d-1_all.deb

    chmod u+x usr/share/jitsi-videobridge/jvb.sh

    rm -rf /build/

    # ice地址配置
    NEW_JITSI_CONFIG="/etc/jitsi/videobridge/sip-communicator.properties"
    echo "org.ice4j.ice.harvest.NAT_HARVESTER_LOCAL_ADDRESS=$INNER_IP" >> $NEW_JITSI_CONFIG
    echo "org.ice4j.ice.harvest.NAT_HARVESTER_PUBLIC_ADDRESS=$INNER_IP" >> $NEW_JITSI_CONFIG

fi

# 重启服务
service jitsi-videobridge2 restart

# Docker容器后台运行,就必须有一个前台进程
dummy=/config/dummy
if [ ! -f "$dummy" ]; then
	touch $dummy
fi
tail -f $dummy