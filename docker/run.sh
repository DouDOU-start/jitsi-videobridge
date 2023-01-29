#!/bin/bash

docker run -itd --name videobridge --net=host doudou/jmeet-videobridge:v1.0.0

docker exec -it videobridge /bin/bash