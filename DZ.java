import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

// 1. Реализация базовых компонентов

// Интерфейс Observer (Наблюдатель)
interface Observer<T> {
    void onNext(T item);
    void onError(Throwable t);
    void onComplete();
}

// Интерфейс Disposable (для отмены подписки)
interface Disposable {
    void dispose(); // Отмена подписки
    boolean isDisposed(); // Проверка, была ли отмена
}

// Класс Observable (Наблюдаемый объект)
class Observable<T> {
    private final List<Subscription<? super T>> subscriptions = new ArrayList<>(); // Хранение подписок
    private final ObservableSource<T> source;  // Использование интерфейса

    // Функциональный интерфейс для источника данных
    interface ObservableSource<T> {
        void subscribe(Observer<? super T> observer);
    }


    // Конструктор принимает ObservableSource
    private Observable(ObservableSource<T> source) {
        this.source = source;
    }

    // Метод subscribe (подписка)
    public Disposable subscribe(Observer<? super T> observer) {
        if (observer == null) {
            throw new NullPointerException("Observer can't be null");
        }
        Subscription<? super T> subscription = new Subscription<>(observer);
        subscriptions.add(subscription);
        source.subscribe(subscription); // Передаем Subscription, чтобы была возможность отмены
        return subscription;
    }

    // Статический метод create()
    public static <T> Observable<T> create(ObservableSource<T> source) {
        if (source == null) {
            throw new NullPointerException("ObservableSource can't be null");
        }
        return new Observable<>(source);
    }

    // Статический метод create() с Consumer (более упрощенный)
    public static <T> Observable<T> create(Consumer<Observer<? super T>> emitter) {
        if (emitter == null) {
            throw new NullPointerException("Emitter can't be null");
        }
        return create(observer -> emitter.accept(observer));
    }

    // 2. Операторы преобразования данных

    // Оператор map()
    public <R> Observable<R> map(Function<? super T, ? extends R> mapper) {
        if (mapper == null) {
            throw new NullPointerException("Mapper function can't be null");
        }
        return create(observer -> {
            subscribe(new Observer<T>() {
                @Override
                public void onNext(T item) {
                    try {
                        R mappedItem = mapper.apply(item);
                        observer.onNext(mappedItem);
                    } catch (Throwable t) {
                        observer.onError(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    observer.onError(t);
                }

                @Override
                public void onComplete() {
                    observer.onComplete();
                }
            });
        });
    }

    // Оператор filter()
    public Observable<T> filter(Predicate<? super T> predicate) {
        if (predicate == null) {
            throw new NullPointerException("Predicate can't be null");
        }
        return create(observer -> {
            subscribe(new Observer<T>() {
                @Override
                public void onNext(T item) {
                    try {
                        if (predicate.test(item)) {
                            observer.onNext(item);
                        }
                    } catch (Throwable t) {
                        observer.onError(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    observer.onError(t);
                }

                @Override
                public void onComplete() {
                    observer.onComplete();
                }
            });
        });
    }

    // Дополнительный оператор для удобства тестирования (не является обязательным, но полезен)
    public Observable<T> doOnNext(Consumer<? super T> consumer) {
        if (consumer == null) {
            throw new NullPointerException("Consumer function can't be null");
        }
        return create(observer -> {
            subscribe(new Observer<T>() {
                @Override
                public void onNext(T item) {
                    try {
                        consumer.accept(item);
                        observer.onNext(item);
                    } catch (Throwable t) {
                        observer.onError(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    observer.onError(t);
                }

                @Override
                public void onComplete() {
                    observer.onComplete();
                }
            });
        });
    }

    // 3. Управление потоками выполнения

    // Интерфейс Scheduler
    interface Scheduler {
        void execute(Runnable task);
    }

    // IO Thread Scheduler
    static class IOThreadScheduler implements Scheduler {
        private final ExecutorService executor = Executors.newCachedThreadPool();

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }

        public void shutdown() {
            executor.shutdown();
        }
    }


    // Computation Scheduler
    static class ComputationScheduler implements Scheduler {
        private final int poolSize = Runtime.getRuntime().availableProcessors(); // количество ядер
        private final ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }

        public void shutdown() {
            executor.shutdown();
        }
    }


    // Single Thread Scheduler
    static class SingleThreadScheduler implements Scheduler {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }

        public void shutdown() {
            executor.shutdown();
        }
    }

    // Оператор subscribeOn()
    public Observable<T> subscribeOn(Scheduler scheduler) {
        if (scheduler == null) {
            throw new NullPointerException("Scheduler can't be null");
        }
        return create(observer -> {
            scheduler.execute(() -> {
                source.subscribe(observer);
            });
        });
    }

    // Оператор observeOn()
    public Observable<T> observeOn(Scheduler scheduler) {
        if (scheduler == null) {
            throw new NullPointerException("Scheduler can't be null");
        }
        return create(observer -> {
            subscribe(new Observer<T>() {
                @Override
                public void onNext(T item) {
                    scheduler.execute(() -> observer.onNext(item));
                }

                @Override
                public void onError(Throwable t) {
                    scheduler.execute(() -> observer.onError(t));
                }

                @Override
                public void onComplete() {
                    scheduler.execute(() -> observer.onComplete());
                }
            });
        });
    }

    // 4. Дополнительные операторы и управление подписками

    // Оператор flatMap()
    public <R> Observable<R> flatMap(Function<? super T, ? extends Observable<? extends R>> mapper) {
        if (mapper == null) {
            throw new NullPointerException("Mapper function can't be null");
        }
        return create(observer -> {
            Disposable mainSubscription = subscribe(new Observer<T>() {
                @Override
                public void onNext(T item) {
                    try {
                        Observable<? extends R> innerObservable = mapper.apply(item);
                        if (innerObservable != null) {
                            innerObservable.subscribe(new Observer<R>() { // Подписка на внутренний Observable
                                @Override
                                public void onNext(R innerItem) {
                                    observer.onNext(innerItem);
                                }

                                @Override
                                public void onError(Throwable t) {
                                    observer.onError(t); // Пробрасываем ошибку
                                }

                                @Override
                                public void onComplete() {
                                    // Ничего не делаем, т.к. нужно дождаться завершения всех внутренних Observables
                                }
                            });
                        }
                    } catch (Throwable t) {
                        observer.onError(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    observer.onError(t); // Пробрасываем ошибку
                }

                @Override
                public void onComplete() {
                    observer.onComplete();  // Дожидаемся завершения всех внутренних
                }
            });
        });
    }



    // Класс для управления подпиской и ее отмены
    private class Subscription<S> implements Observer<S>, Disposable {
        private final Observer<? super S> actualObserver;
        private volatile boolean isDisposed = false; // Флаг для проверки отмены

        public Subscription(Observer<? super S> actualObserver) {
            this.actualObserver = actualObserver;
        }

        @Override
        public void onNext(S item) {
            if (!isDisposed) {
                actualObserver.onNext(item);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (!isDisposed) {
                actualObserver.onError(t);
                dispose(); // Отменяем подписку при ошибке
            }
        }

        @Override
        public void onComplete() {
            if (!isDisposed) {
                actualObserver.onComplete();
                dispose(); // Отменяем подписку при завершении
            }
        }

        @Override
        public void dispose() {
            isDisposed = true;
            subscriptions.remove(this); // Удаляем подписку из списка
        }

        @Override
        public boolean isDisposed() {
            return isDisposed;
        }
    }
}