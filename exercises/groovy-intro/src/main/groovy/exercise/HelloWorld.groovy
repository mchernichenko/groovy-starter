package exercise

import groovy.transform.PackageScope

// по умолчанию все классы и методы имеют модификатор доступа ао умолчанию public
// ля полей - private
class HelloWorld {

    // @PackageScope // если нужно изменить модификатор доступа
    static void main(String[] args) {
        println("Hello World")
        println "Hello World"
    }
}
