# faas-platform-demo

this project shows how you might build an api gateway of sorts that would scale down its services to 0.

For example, right now it supports returning "hello world" for all requests
using [this hello world image]
(https://hub.docker.com/r/hashicorp/http-echo/).

When a request comes in,
it is buffered until the container is started,
then forwarded to the container,
and status/headers/body are sent back to original client.

The different functions can be determined by hostname in the request.
Currently, they are determined by first path segment, so

```
name
```

from `curl localhost:8080/name/any/nested/path`.

For example:

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.0.6)

 INFO : Starting FaasApplication using Java 17...
DEBUG : Running with Spring Boot v3.0.6, Spring v6.0.8
 INFO : The following 1 profile is active: "local"
 INFO : BeanFactory id=$(uuidgen)
 INFO : Exposing 1 endpoint(s) beneath base path '/actuator'
 INFO : Netty started on port 8080
 INFO : Started FaasApplication in 2.107 seconds (process running for 2.48)
 INFO : creating sample function 'name'
 INFO : added
DEBUG : found function with name: name
DEBUG : created container with id: 688a16663e6
DEBUG : decrementing runner for function: name
DEBUG : decrementing runner for function: name, instances: null
DEBUG : runner is stopping for function: name (containerId: 688a16663e6)
DEBUG : runner is calling docker to stop function: name
DEBUG : runner told docker to stop function: name
DEBUG : runner is done stopping function: name
```
