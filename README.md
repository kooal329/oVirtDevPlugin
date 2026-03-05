# oVirt Command Explorer (IntelliJ IDEA Plugin)

Плагин для ускорения разработки команд oVirt Engine в IntelliJ IDEA.

## Реализованные возможности

- **Навигация по командам**: индексирует классы-наследники `CommandBase<T>` и отображает параметры и внутренние вызовы `runInternalAction`.
- **Show Command Usages**: действие в контекстном меню для отображения всех мест использования команды.
- **Command Call Graph**: Tool Window с древовидной визуализацией цепочек вызовов команд.
- **Анализ параметров**: показывает, какой параметр используется в `CommandBase<T>`, и обратный поиск команд по параметру.
- **Wizard `New → oVirt Command`**: генерирует `*Command` и `*Parameters` классы с boilerplate-кодом.
- **Горячие клавиши**:
  - `Cmd/Ctrl + Shift + C` — быстрый поиск команды.
  - `Cmd/Ctrl + Shift + G` — открыть граф команд.

## Архитектура

- `CommandIndexService` — PSI/текстовый анализ Java-файлов проекта.
- `actions/*` — действия меню и хоткеев.
- `ui/*` — Tool Window и визуализация графа.
- `wizard/*` — генерация новых команд.

## Запуск

```bash
./gradlew buildPlugin
```

Для отладки в sandbox IDE:

```bash
./gradlew runIde
```
