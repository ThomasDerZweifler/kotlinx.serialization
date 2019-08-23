/*
 * Copyright 2017-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization

import kotlinx.serialization.PolymorphicClassDescriptor.kind
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.internal.SerialClassDescImpl
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlin.reflect.KClass

/**
 * A [SerialDescriptor] for polymorphic serialization with special kind.
 *
 * Currently, it has no guarantees neither on its reference transparency nor its [elementDescriptors], only on [kind].
 */
public object PolymorphicClassDescriptor : SerialClassDescImpl("kotlin.Any") {
    public override val kind: SerialKind = UnionKind.POLYMORPHIC

    init {
        // serial ids would be assigned automatically, since
        // we decided not to support @SerialInfo annotations
        // in custom serializers code
        addElement("class")
        addElement("value")
    }
}

/**
 * This class provides support for multiplatform polymorphic serialization.
 * Due to security and reflection usage concerns, all serializable implementations of some abstract class must be registered in advance.
 * However, it allows registering subclasses in runtime, not compile-time. For example, it allows adding additional subclasses to the registry
 * that were defined in a separate module, dependent on the base module with the base class.
 *
 * Polymorphic serialization is enabled automatically by default only for types that are interfaces or [Serializable] abstract classes.
 * To enable this feature explicitly on other types, use `@SerializableWith(PolymorphicSerializer::class)`
 * or just [Polymorphic] annotation on the property.
 *
 * Another security requirement is that we only allow to register subclasses in the scope of a [baseClass]
 * The motivation for this is easily understandable from the example:
 *
 * ```
 * abstract class BaseRequest()
 * @Serializable data class RequestA(val id: Int): BaseRequest()
 * @Serializable data class RequestB(val s: String): BaseRequest()
 *
 * abstract class BaseResponse()
 * @Serializable data class ResponseC(val payload: Long): BaseResponse()
 * @Serializable data class ResponseD(val payload: ByteArray): BaseResponse()
 *
 * @Serializable data class Message(
 *     @Polymorphic val request: BaseRequest,
 *     @Polymorphic val response: BaseResponse
 * )
 * ```
 * In this example, both request and response in `Message` are serializable with [PolymorphicSerializer].
 *
 * `BaseRequest` and `BaseResponse` are base classes and they are captured during compile time by the plugin.
 * Yet [PolymorphicSerializer] for `BaseRequest` should only allow `RequestA` and `RequestB` serializers, and none of the response's serializers.
 *
 * This is achieved via special registration function in the module:
 * ```
 * val requestAndResponseModule = SerializersModule {
 *     polymorphic(BaseRequest::class) {
 *         RequestA::class with RequestA.serializer()
 *         RequestB::class with RequestB.serializer()
 *     }
 *     polymorphic(BaseResponse::class) {
 *         ResponseC::class with ResponseC.serializer()
 *         ResponseD::class with ResponseD.serializer()
 *     }
 * }
 * ```
 *
 * By default (without special support from [Encoder]), polymorphic values are serialized as list with
 * two elements: fully-qualified class name (String) and the object itself.
 *
 * @see SerializersModule
 * @see SerializersModuleBuilder.polymorphic
 */
public class PolymorphicSerializer<T : Any>(private val baseClass: KClass<T>) : AbstractPolymorphicSerializer<T>() {
    public override val descriptor: SerialDescriptor = PolymorphicClassDescriptor

    /**
     * Lookups a polymorphic serializer by given [klassName] in the context of [decoder] by the current [base class][baseClass].
     * Throws [SerializationException] if serializer is not found.
     */
    public override fun findPolymorphicSerializer(
        decoder: CompositeDecoder,
        klassName: String
    ): KSerializer<out T> = decoder.context.getPolymorphic(baseClass, klassName)
            ?: throwSubtypeNotRegistered(klassName, baseClass)

    /**
     * Lookups a polymorphic serializer by given [value] in the context of [encoder] by the current [base class][baseClass].
     * Throws [SerializationException] if serializer is not found.
     */
    @Suppress("UNCHECKED_CAST")
    public override fun findPolymorphicSerializer(
        encoder: Encoder,
        value: T
    ): KSerializer<out T> =
        encoder.context.getPolymorphic(baseClass, value) ?: throwSubtypeNotRegistered(value::class, baseClass)
}


private fun throwSubtypeNotRegistered(subClassName: String, baseClass: KClass<*>): Nothing =
    throw SerializationException("$subClassName is not registered for polymorphic serialization in the scope of $baseClass")

private fun throwSubtypeNotRegistered(subClass: KClass<*>, baseClass: KClass<*>): Nothing = throwSubtypeNotRegistered(subClass.toString(), baseClass)
