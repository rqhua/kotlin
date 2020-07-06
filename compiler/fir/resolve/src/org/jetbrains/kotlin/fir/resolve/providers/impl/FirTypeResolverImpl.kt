/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.providers.impl

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.FirTypeResolver
import org.jetbrains.kotlin.fir.resolve.constructType
import org.jetbrains.kotlin.fir.resolve.firSymbolProvider
import org.jetbrains.kotlin.fir.resolve.qualifierResolver
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassifierSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.FirImplicitBuiltinTypeRef
import org.jetbrains.kotlin.name.ClassId

class FirTypeResolverImpl(private val session: FirSession) : FirTypeResolver {

    private val symbolProvider by lazy {
        session.firSymbolProvider
    }

    private data class ClassIdInSession(val session: FirSession, val id: ClassId)

    private val implicitBuiltinTypeSymbols = mutableMapOf<ClassIdInSession, FirClassLikeSymbol<*>>()

    // TODO: get rid of session used here, and may be also of the cache above (see KT-30275)
    private fun resolveBuiltInQualified(id: ClassId, session: FirSession): FirClassLikeSymbol<*> {
        val nameInSession = ClassIdInSession(session, id)
        return implicitBuiltinTypeSymbols.getOrPut(nameInSession) {
            symbolProvider.getClassLikeSymbolByFqName(id)!!
        }
    }

    override fun resolveToSymbol(
        typeRef: FirTypeRef,
        scope: FirScope
    ): Pair<FirClassifierSymbol<*>?, ConeSubstitutor?> {
        return when (typeRef) {
            is FirResolvedTypeRef -> {
                val resultSymbol = typeRef.coneTypeSafe<ConeLookupTagBasedType>()?.lookupTag?.let(symbolProvider::getSymbolByLookupTag)
                resultSymbol to null
            }

            is FirUserTypeRef -> {
                val qualifierResolver = session.qualifierResolver
                var resolvedSymbol: FirClassifierSymbol<*>? = null
                var substitutor: ConeSubstitutor? = null
                scope.processClassifiersByNameWithSubstitution(typeRef.qualifier.first().name) { symbol, substitutorFromScope ->
                    if (resolvedSymbol != null) return@processClassifiersByNameWithSubstitution
                    resolvedSymbol = when (symbol) {
                        is FirClassLikeSymbol<*> -> {
                            if (typeRef.qualifier.size == 1) {
                                symbol
                            } else {
                                qualifierResolver.resolveSymbolWithPrefix(typeRef.qualifier, symbol.classId)
                            }
                        }
                        is FirTypeParameterSymbol -> {
                            assert(typeRef.qualifier.size == 1)
                            symbol
                        }
                        else -> error("!")
                    }
                    substitutor = substitutorFromScope
                }

                // TODO: Imports
                val resultSymbol: FirClassifierSymbol<*>? = resolvedSymbol ?: qualifierResolver.resolveSymbol(typeRef.qualifier)
                resultSymbol to substitutor
            }

            is FirImplicitBuiltinTypeRef -> {
                resolveBuiltInQualified(typeRef.id, session) to null
            }

            else -> null to null
        }
    }

    private fun resolveUserType(typeRef: FirUserTypeRef, symbol: FirClassifierSymbol<*>?, substitutor: ConeSubstitutor?): ConeKotlinType {
        if (symbol == null) {
            return ConeKotlinErrorType("Symbol not found, for `${typeRef.render()}`")
        }
        return symbol.constructType(typeRef.qualifier, typeRef.isMarkedNullable, symbolOriginSession = session, typeRef.annotations.computeTypeAttributes(), substitutor)
    }

    private fun createFunctionalType(typeRef: FirFunctionTypeRef): ConeClassLikeType {
        val parameters =
            listOfNotNull(typeRef.receiverTypeRef?.coneType) +
                    typeRef.valueParameters.map { it.returnTypeRef.coneType } +
                    listOf(typeRef.returnTypeRef.coneType)
        val classId = if (typeRef.isSuspend) {
            KotlinBuiltIns.getSuspendFunctionClassId(typeRef.parametersCount)
        } else {
            KotlinBuiltIns.getFunctionClassId(typeRef.parametersCount)
        }
        val attributes = typeRef.annotations.computeTypeAttributes()
        return ConeClassLikeTypeImpl(
            resolveBuiltInQualified(classId, session).toLookupTag(),
            parameters.toTypedArray(),
            typeRef.isMarkedNullable,
            attributes
        )
    }

    override fun resolveType(
        typeRef: FirTypeRef,
        scope: FirScope
    ): ConeKotlinType {
        return when (typeRef) {
            is FirResolvedTypeRef -> typeRef.type
            is FirUserTypeRef -> {
                val (symbol, substitutor) = resolveToSymbol(typeRef, scope)
                resolveUserType(typeRef, symbol, substitutor)
            }
            is FirFunctionTypeRef -> createFunctionalType(typeRef)
            is FirDelegatedTypeRef -> resolveType(typeRef.typeRef, scope)
            is FirDynamicTypeRef -> ConeKotlinErrorType("Not supported: ${typeRef::class.simpleName}")
            else -> error("!")
        }
    }
}
