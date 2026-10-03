# Документация system tests

Fixtures тестов не являются customer release для развёртывания. Они создают
предсказуемые роли, конфигурацию и provider bindings, чтобы проверять
межсервисные границы. Успешный system test не доказывает доступность внешнего
S3, Keycloak или production BPMN-контура.

Перед изменением public API, configuration или SPI обновляйте тестовый
контракт в этом модуле. Порядок доступных проверок —
[../../docs/testing.md](../../docs/testing.md).
