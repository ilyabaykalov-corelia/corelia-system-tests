# corelia-system-tests

Модуль проверяет межсервисные контракты Corelia с изолированным OIDC issuer,
fixture configuration и тестовыми provider-ами/инфраструктурой. Это не
production service и не заменяет проверку реального customer deployment.

Запускается вместе с reactor:

```bash
./scripts/test.sh
# либо
mvn -pl corelia-system-tests -am test
```

Тесты охватывают public/internal API, mTLS/JWT boundaries, configuration и
согласование document/workflow/attachment сценариев. Отдельные Docker smoke
проверки включайте только при подготовленной Docker-среде согласно
[testing](../docs/testing.md).
