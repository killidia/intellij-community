// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.hints.ParameterHintsPass;
import com.intellij.codeInsight.lookup.CommaTailType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NullableComputable;
import com.intellij.pom.java.JavaFeature;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.impl.source.resolve.CompletionParameterTypeInferencePolicy;
import com.intellij.psi.impl.source.resolve.DefaultParameterTypeInferencePolicy;
import com.intellij.psi.impl.source.resolve.ParameterTypeInferencePolicy;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.*;
import com.intellij.util.ArrayUtil;
import com.intellij.util.JavaPsiConstructorUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Stack;
import com.siyeh.ig.psiutils.TypeUtils;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import com.siyeh.ig.testFrameworks.AssertHint;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;

@Service(Service.Level.PROJECT)
public final class ExpectedTypesProvider {
  private static final ExpectedTypeInfo VOID_EXPECTED = createInfoImpl(PsiTypes.voidType(), ExpectedTypeInfo.TYPE_OR_SUBTYPE,
                                                                       PsiTypes.voidType(), TailTypes.semicolonType());

  private static final Logger LOG = Logger.getInstance(ExpectedTypesProvider.class);

  public static ExpectedTypesProvider getInstance(@NotNull Project project) {
    return project.getService(ExpectedTypesProvider.class);
  }

  private static final int MAX_COUNT = 50;
  private static final ExpectedClassProvider ourGlobalScopeClassProvider = new ExpectedClassProvider() {
    @Override
    public PsiField @NotNull [] findDeclaredFields(@NotNull PsiManager manager, @NotNull String name) {
      final PsiShortNamesCache cache = PsiShortNamesCache.getInstance(manager.getProject());
      GlobalSearchScope scope = GlobalSearchScope.allScope(manager.getProject());
      return cache.getFieldsByName(name, scope);
    }

    @Override
    public PsiMethod @NotNull [] findDeclaredMethods(@NotNull PsiManager manager, @NotNull String name) {
      Project project = manager.getProject();
      final PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
      GlobalSearchScope sources = GlobalSearchScope.projectScope(project);
      GlobalSearchScope libraries = GlobalSearchScope.notScope(sources);
      PsiMethod[] sourceMethods = cache.getMethodsByNameIfNotMoreThan(name, sources, MAX_COUNT);
      if (sourceMethods.length >= MAX_COUNT) return sourceMethods;
      PsiMethod[] libraryMethods = cache.getMethodsByNameIfNotMoreThan(name, libraries, MAX_COUNT-sourceMethods.length);
      return ArrayUtil.mergeArrays(sourceMethods, libraryMethods);
    }
  };

  public static @NotNull ExpectedTypeInfo createInfo(@NotNull  PsiType type, @ExpectedTypeInfo.Type int kind, PsiType defaultType, @NotNull TailType tailType) {
    return createInfoImpl(type, kind, defaultType, tailType);
  }

  private static @NotNull ExpectedTypeInfoImpl createInfoImpl(@NotNull PsiType type, PsiType defaultType) {
    return createInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, defaultType, TailTypes.noneType());
  }

  private static @NotNull ExpectedTypeInfoImpl createInfoImpl(@NotNull PsiType type, @ExpectedTypeInfo.Type int kind, PsiType defaultType, @NotNull TailType tailType) {
    return new ExpectedTypeInfoImpl(type, kind, defaultType, tailType, null, ExpectedTypeInfoImpl.NULL);
  }

  private static @NotNull ExpectedTypeInfoImpl createInfoImpl(@NotNull PsiType type,
                                                              @ExpectedTypeInfo.Type int kind,
                                                              PsiType defaultType,
                                                              @NotNull TailType tailType,
                                                              PsiMethod calledMethod,
                                                              Supplier<String> expectedName) {
    return new ExpectedTypeInfoImpl(type, kind, defaultType, tailType, calledMethod, expectedName);
  }

  public static @Nullable ExpectedTypeInfo getSingleExpectedTypeForCompletion(@Nullable PsiExpression expr) {
    ExpectedTypeInfo[] expectedTypes = getExpectedTypes(expr, true, ourGlobalScopeClassProvider, false, false, 1);
    if (expectedTypes.length > 0) {
      return expectedTypes[0];
    }
    return null;
  }

  public static ExpectedTypeInfo @NotNull [] getExpectedTypes(@Nullable PsiExpression expr, boolean forCompletion) {
    return getExpectedTypes(expr, forCompletion, false, false);
  }

  public static ExpectedTypeInfo @NotNull [] getExpectedTypes(@Nullable PsiExpression expr,
                                                              boolean forCompletion,
                                                              boolean voidable,
                                                              boolean usedAfter) {
    return getExpectedTypes(expr, forCompletion, ourGlobalScopeClassProvider, voidable, usedAfter);
  }

  public static ExpectedTypeInfo @NotNull [] getExpectedTypes(@Nullable PsiExpression expr,
                                                              boolean forCompletion,
                                                              ExpectedClassProvider classProvider,
                                                              boolean usedAfter) {
    return getExpectedTypes(expr, forCompletion, classProvider, false, usedAfter);
  }

  public static ExpectedTypeInfo @NotNull [] getExpectedTypes(@Nullable PsiExpression expr,
                                                              boolean forCompletion,
                                                              ExpectedClassProvider classProvider,
                                                              boolean voidable,
                                                              boolean usedAfter) {
    return getExpectedTypes(expr, forCompletion, classProvider, voidable, usedAfter, Integer.MAX_VALUE);
  }

  private static ExpectedTypeInfo @NotNull [] getExpectedTypes(@Nullable PsiExpression expr,
                                                               boolean forCompletion,
                                                               ExpectedClassProvider classProvider,
                                                               boolean voidable,
                                                               boolean usedAfter,
                                                               int maxCandidates) {
    if (expr == null) return ExpectedTypeInfo.EMPTY_ARRAY;
    PsiElement parent = expr.getParent();
    PsiFunctionalExpression functionalExpression = extractFunctionalExpression(expr);
    if (functionalExpression != null) {
      final Collection<? extends PsiType> types = FunctionalInterfaceSuggester.suggestFunctionalInterfaces(functionalExpression);
      if (types.isEmpty()) {
        return ExpectedTypeInfo.EMPTY_ARRAY;
      }
      else {
        final ExpectedTypeInfo[] result = new ExpectedTypeInfo[types.size()];
        int i = 0;
        for (PsiType type : types) {
          result[i++] =
            new ExpectedTypeInfoImpl(type, ExpectedTypeInfo.TYPE_SAME_SHAPED, type, TailTypes.noneType(), null, ExpectedTypeInfoImpl.NULL);
        }
        return result;
      }
    }
    MyParentVisitor visitor = new MyParentVisitor(expr, forCompletion, classProvider, voidable, usedAfter, maxCandidates);
    if (parent != null) {
      parent.accept(visitor);
    }
    ExpectedTypeInfo[] result = visitor.getResult();
    if (forCompletion) {
      return ContainerUtil.map2Array(result, ExpectedTypeInfo.class, i -> ((ExpectedTypeInfoImpl)i).fixUnresolvedTypes(expr));
    }
    return result;
  }

  private static PsiFunctionalExpression extractFunctionalExpression(PsiExpression expr) {
    PsiElement parent = expr.getParent();
    if (expr instanceof PsiFunctionalExpression && parent instanceof PsiExpressionStatement && !(parent.getParent() instanceof PsiSwitchLabeledRuleStatement)) {
      return (PsiFunctionalExpression)expr;
    }
    parent = PsiTreeUtil.skipParentsOfType(expr, PsiParenthesizedExpression.class);
    if (parent instanceof PsiAssignmentExpression &&
        parent.getParent() instanceof PsiExpressionStatement &&
        PsiTreeUtil.isAncestor(((PsiAssignmentExpression)parent).getLExpression(), expr, false)) {
      return ObjectUtils.tryCast(((PsiAssignmentExpression)parent).getRExpression(), PsiFunctionalExpression.class);
    }
    return null;
  }

  public static PsiType @NotNull [] processExpectedTypes(ExpectedTypeInfo @NotNull [] infos,
                                                         @NotNull PsiTypeVisitor<? extends PsiType> visitor, @NotNull Project project) {
    LinkedHashSet<PsiType> set = new LinkedHashSet<>();
    for (ExpectedTypeInfo info : infos) {
      ExpectedTypeInfoImpl infoImpl = (ExpectedTypeInfoImpl)info;

      if (infoImpl.getDefaultType() instanceof PsiClassType) {
        JavaResolveResult result = ((PsiClassType)infoImpl.getDefaultType()).resolveGenerics();
        PsiClass aClass = (PsiClass)result.getElement();
        if (aClass instanceof PsiAnonymousClass) {
          processType(((PsiAnonymousClass)aClass).getBaseClassType(), visitor, set);
          ((PsiAnonymousClass)aClass).getBaseClassType().accept(visitor);
        }
        else {
          processType(infoImpl.getDefaultType(), visitor, set);
        }
      }
      else {
        processType(infoImpl.getDefaultType(), visitor, set);
      }

      if (infoImpl.getKind() == ExpectedTypeInfo.TYPE_OR_SUPERTYPE) {
        processAllSuperTypes(infoImpl.getType(), visitor, project, set, new HashSet<>());
      }
      else if (infoImpl.getKind() == ExpectedTypeInfo.TYPE_OR_SUBTYPE) {
        if (infoImpl.getType() instanceof PsiPrimitiveType) {
          processPrimitiveTypeAndSubtypes((PsiPrimitiveType)infoImpl.getType(), visitor, set);
        }
        //else too expensive to search
      }
    }

    return set.toArray(PsiType.createArray(set.size()));
  }

  private static void processType(@NotNull PsiType type, @NotNull PsiTypeVisitor<? extends PsiType> visitor, @NotNull Set<? super PsiType> typeSet) {
    PsiType accepted = type.accept(visitor);
    if (accepted != null) typeSet.add(accepted);
  }

  private static void processPrimitiveTypeAndSubtypes(@NotNull PsiPrimitiveType type,
                                                      @NotNull PsiTypeVisitor<? extends PsiType> visitor,
                                                      @NotNull Set<? super PsiType> set) {
    if (type.equals(PsiTypes.booleanType()) || type.equals(PsiTypes.voidType()) || type.equals(PsiTypes.nullType())) return;

    for (PsiPrimitiveType primitiveType : PsiTypes.primitiveTypes()) {
      processType(primitiveType, visitor, set);
      if (primitiveType.equals(type)) return;
    }
  }

  public static void processAllSuperTypes(@NotNull PsiType type, @NotNull PsiTypeVisitor<? extends PsiType> visitor, @NotNull Project project, @NotNull Set<? super PsiType> set, @NotNull Set<? super PsiType> visited) {
    if (!visited.add(type)) return;

    if (type instanceof PsiPrimitiveType) {
      if (type.equals(PsiTypes.booleanType()) || type.equals(PsiTypes.voidType()) || type.equals(PsiTypes.nullType())) return;

      List<PsiPrimitiveType> primitiveTypes = PsiTypes.primitiveTypes();
      Stack<PsiType> stack = new Stack<>();
      for (int i = primitiveTypes.size() - 1; i >= 0; i--) {
        PsiPrimitiveType primitiveType = primitiveTypes.get(i);
        if (primitiveTypes.get(i).equals(type)) break;
        //it is already processed before
        if (primitiveType.equals(PsiTypes.booleanType())) continue;
        stack.push(primitiveType);
      }
      while (!stack.empty()) {
        processType(stack.pop(), visitor, set);
      }
    }
    else{
      PsiManager manager = PsiManager.getInstance(project);
      GlobalSearchScope resolveScope = type.getResolveScope();
      if (resolveScope == null) resolveScope = GlobalSearchScope.allScope(project);
      PsiClassType objectType = PsiType.getJavaLangObject(manager, resolveScope);
      processType(objectType, visitor, set);

      if (type instanceof PsiClassType) {
        for (PsiType superType : type.getSuperTypes()) {
          processType(superType, visitor, set);
          processAllSuperTypes(superType, visitor, project, set, visited);
        }
      }
    }
  }

  private static final class MyParentVisitor extends JavaElementVisitor {
    private static final int MAX_VAR_HOPS = 3;
    private PsiExpression myExpr;
    private int myHops = 0;
    private final boolean myForCompletion;
    private final boolean myUsedAfter;
    private final int myMaxCandidates;
    private final ExpectedClassProvider myClassProvider;
    private final boolean myVoidable;
    final List<ExpectedTypeInfo> myResult = new ArrayList<>();
    private static final @NonNls String LENGTH_SYNTHETIC_ARRAY_FIELD = "length";

    private MyParentVisitor(PsiExpression expr,
                            boolean forCompletion,
                            ExpectedClassProvider classProvider,
                            boolean voidable,
                            boolean usedAfter,
                            int maxCandidates) {
      myExpr = expr;
      myForCompletion = forCompletion;
      myClassProvider = classProvider;
      myVoidable = voidable;
      myUsedAfter = usedAfter;
      myMaxCandidates = maxCandidates;
    }

    public ExpectedTypeInfo @NotNull [] getResult() {
      if (myResult.size() > myMaxCandidates) {
        return myResult.subList(0, myMaxCandidates).toArray(ExpectedTypeInfo.EMPTY_ARRAY);
      }
      return myResult.toArray(ExpectedTypeInfo.EMPTY_ARRAY);
    }

    @Override
    public void visitParenthesizedExpression(@NotNull PsiParenthesizedExpression expression) {
      PsiElement parent = expression.getParent();
      if (parent != null) {
        final MyParentVisitor visitor = new MyParentVisitor(expression, myForCompletion, myClassProvider, myVoidable, myUsedAfter,
                                                            myMaxCandidates);
        parent.accept(visitor);
        for (ExpectedTypeInfo info : visitor.myResult) {
          myResult.add(createInfoImpl(info.getType(), info.getKind(), info.getDefaultType(), JavaTailTypes.RPARENTH, info.getCalledMethod(),
                                      ((ExpectedTypeInfoImpl)info)::getExpectedName));
        }
      }
    }

    @Override
    public void visitAnnotationMethod(@NotNull PsiAnnotationMethod method) {
      if (myExpr == method.getDefaultValue()) {
        final PsiType type = method.getReturnType();
        if (type != null) {
          myResult.add(createInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, TailTypes.semicolonType()));
        }
      }
    }

    @Override
    public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
      if (myForCompletion) {
        final MyParentVisitor visitor = new MyParentVisitor(expression, true, myClassProvider, myVoidable, myUsedAfter, myMaxCandidates);
        expression.getParent().accept(visitor);
        myResult.addAll(visitor.myResult);
        return;
      }

      String referenceName = expression.getReferenceName();
      if (referenceName != null) {
        final PsiElement parent = expression.getParent();
        if (parent instanceof PsiMethodCallExpression) {
          Collections.addAll(myResult, findClassesWithDeclaredMethod((PsiMethodCallExpression)parent));
        }
        else if (parent instanceof PsiVariable ||
                 parent instanceof PsiExpression) {
          if (LENGTH_SYNTHETIC_ARRAY_FIELD.equals(referenceName)) {
            myResult.addAll(anyArrayType());
          }
          else {
            Collections.addAll(myResult, findClassesWithDeclaredField(expression));
          }
        }
      }
    }

    @Override
    public void visitExpressionStatement(@NotNull PsiExpressionStatement statement) {
      if (statement.getParent() instanceof PsiSwitchLabeledRuleStatement) {
        PsiSwitchBlock block = ((PsiSwitchLabeledRuleStatement)statement.getParent()).getEnclosingSwitchBlock();
        if (block instanceof PsiSwitchExpression) {
          Collections.addAll(myResult, getExpectedTypes((PsiExpression)block, myForCompletion));
          return;
        }
      }
      if (myVoidable) {
        myResult.add(VOID_EXPECTED);
      }
    }

    @Override
    public void visitYieldStatement(@NotNull PsiYieldStatement statement) {
      PsiSwitchExpression expression = statement.findEnclosingExpression();
      if (expression != null) {
        Collections.addAll(myResult, getExpectedTypes(expression, myForCompletion));
      }
    }

    @Override
    public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
      myExpr = (PsiExpression)myExpr.getParent();
      expression.getParent().accept(this);
    }

    @Override
    public void visitAnnotationArrayInitializer(@NotNull PsiArrayInitializerMemberValue initializer) {
      PsiElement parent = initializer.getParent();
      while (parent instanceof PsiArrayInitializerMemberValue) {
        parent = parent.getParent();
      }
      final PsiType type;
      if (parent instanceof PsiNameValuePair) {
        type = getAnnotationMethodType((PsiNameValuePair)parent);
      }
      else {
        type = ((PsiAnnotationMethod)parent).getReturnType();
      }
      if (type instanceof PsiArrayType) {
        final PsiType componentType = ((PsiArrayType)type).getComponentType();
        myResult.add(createInfoImpl(componentType, componentType));
      }
    }

    @Override
    public void visitNameValuePair(@NotNull PsiNameValuePair pair) {
      final PsiType type = getAnnotationMethodType(pair);
      if (type == null) return;
      if (type instanceof PsiArrayType) {
        PsiType componentType = ((PsiArrayType)type).getComponentType();
        myResult.add(createInfoImpl(componentType, componentType));
      }
      else {
        myResult.add(createInfoImpl(type, type));
      }
    }

    private static @Nullable PsiType getAnnotationMethodType(@NotNull PsiNameValuePair pair) {
      final PsiReference reference = pair.getReference();
      if (reference != null) {
        final PsiElement method = reference.resolve();
        if (method instanceof PsiMethod) {
          return ((PsiMethod)method).getReturnType();
        }
      }
      return null;
    }

    @Override
    public void visitLambdaExpression(@NotNull PsiLambdaExpression lambdaExpression) {
      super.visitLambdaExpression(lambdaExpression);
      final PsiType functionalInterfaceType = lambdaExpression.getFunctionalInterfaceType();
      final PsiMethod scopeMethod = LambdaUtil.getFunctionalInterfaceMethod(functionalInterfaceType);
      if (scopeMethod != null) {
        visitMethodReturnType(scopeMethod, LambdaUtil.getFunctionalInterfaceReturnType(functionalInterfaceType), 
                              JavaCompletionUtil.insertSemicolonAfter(lambdaExpression));
      }
    }

    @Override
    public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
      final PsiMethod method;
      final PsiType type;
      final boolean tailTypeSemicolon;
      final NavigatablePsiElement psiElement = PsiTreeUtil.getParentOfType(statement, PsiLambdaExpression.class, PsiMethod.class);
      if (psiElement instanceof PsiLambdaExpression) {
        final PsiType functionalInterfaceType = ((PsiLambdaExpression)psiElement).getFunctionalInterfaceType();
        method = LambdaUtil.getFunctionalInterfaceMethod(functionalInterfaceType);
        type = LambdaUtil.getFunctionalInterfaceReturnType(functionalInterfaceType);
        tailTypeSemicolon = JavaCompletionUtil.insertSemicolonAfter((PsiLambdaExpression)psiElement);
      }
      else if (psiElement instanceof PsiMethod) {
        method = (PsiMethod)psiElement;
        type = method.getReturnType();
        tailTypeSemicolon = true;
      } else {
        method = null;
        type = null;
        tailTypeSemicolon = true;
      }
      if (method != null) {
        visitMethodReturnType(method, type, tailTypeSemicolon);
      }

    }

    private void visitMethodReturnType(PsiMethod scopeMethod, PsiType type, boolean tailTypeSemicolon) {
      if (type != null) {
        Supplier<String> expectedName;
        if (PropertyUtilBase.isSimplePropertyAccessor(scopeMethod)) {
          expectedName = () -> PropertyUtilBase.getPropertyName(scopeMethod);
        }
        else {
          expectedName = ExpectedTypeInfoImpl.NULL;
        }

        myResult.add(createInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type,
                                    tailTypeSemicolon ? TailTypes.semicolonType() : TailTypes.noneType(), null, expectedName));
      }
    }

    @Override
    public void visitIfStatement(@NotNull PsiIfStatement statement) {
      myResult.add(
        createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(), JavaTailTypes.IF_RPARENTH));
    }

    @Override
    public void visitWhileStatement(@NotNull PsiWhileStatement statement) {
      myResult.add(
        createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(), JavaTailTypes.WHILE_RPARENTH));
    }

    @Override
    public void visitDoWhileStatement(@NotNull PsiDoWhileStatement statement) {
      myResult.add(
        createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(), JavaTailTypes.WHILE_RPARENTH));
    }

    @Override
    public void visitForStatement(@NotNull PsiForStatement statement) {
      if (myExpr.equals(statement.getCondition())) {
        myResult.add(createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(),
                                    TailTypes.semicolonType()));
      }
    }

    @Override
    public void visitAssertStatement(@NotNull PsiAssertStatement statement) {
      if (statement.getAssertDescription() == myExpr) {
        final PsiClassType stringType = PsiType.getJavaLangString(myExpr.getManager(), myExpr.getResolveScope());
        myResult.add(createInfoImpl(stringType, ExpectedTypeInfo.TYPE_STRICTLY, stringType, TailTypes.semicolonType()));
      }
      else {
        myResult.add(createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(),
                                    TailTypes.semicolonType()));
      }
    }

    @Override
    public void visitSwitchLabelStatement(@NotNull PsiSwitchLabelStatement statement) {
      processGuard(statement);
    }

    @Override
    public void visitSwitchLabeledRuleStatement(@NotNull PsiSwitchLabeledRuleStatement statement) {
      processGuard(statement);
    }

    private void processGuard(@NotNull PsiSwitchLabelStatementBase statement) {
      if (!myExpr.equals(statement.getGuardExpression())) {
        return;
      }
      final PsiSwitchBlock switchBlock = statement.getEnclosingSwitchBlock();
      if (switchBlock != null) {
        final TailType caseTail = JavaTailTypes.forSwitchLabel(switchBlock);
        myResult.add(createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(), caseTail));
      }
    }

    @Override
    public void visitForeachStatement(@NotNull PsiForeachStatement statement) {
      if (myExpr.equals(statement.getIteratedValue())) {
        PsiParameter iterationParameter = statement.getIterationParameter();
        PsiType type = iterationParameter.getType();

        if (PsiTypes.nullType().equals(type)) return;

        PsiType arrayType = type.createArrayType();
        myResult.add(createInfoImpl(arrayType, arrayType));

        PsiManager manager = statement.getManager();
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(manager.getProject());
        PsiClass iterableClass =
          JavaPsiFacade.getInstance(manager.getProject()).findClass("java.lang.Iterable", statement.getResolveScope());
        if (iterableClass != null && iterableClass.getTypeParameters().length == 1) {
          Map<PsiTypeParameter, PsiType> map = new HashMap<>();
          map.put(iterableClass.getTypeParameters()[0], PsiWildcardType.createExtends(manager, type));
          PsiType iterableType = factory.createType(iterableClass, factory.createSubstitutor(map));
          myResult.add(createInfoImpl(iterableType, iterableType));
        }
      }
    }

    @Override
    public void visitSwitchStatement(@NotNull PsiSwitchStatement statement) {
      processSwitchBlock(statement);
    }

    @Override
    public void visitSwitchExpression(@NotNull PsiSwitchExpression expression) {
      processSwitchBlock(expression);
    }

    public void processSwitchBlock(@NotNull PsiSwitchBlock statement) {
      if (statement.getExpression() == this.myExpr) {
        List<ExpectedTypeInfo> collectedTypes = collectFromLabels(statement);
        if (!collectedTypes.isEmpty()) {
          myResult.addAll(collectedTypes);
          return;
        }
      }
      myResult.add(createInfoImpl(PsiTypes.intType(), PsiTypes.intType()));
      LanguageLevel level = PsiUtil.getLanguageLevel(statement);
      if (JavaFeature.ENUMS.isSufficient(level)) {
        PsiClassType enumType = TypeUtils.getType(CommonClassNames.JAVA_LANG_ENUM, statement);
        myResult.add(createInfoImpl(enumType, enumType));
      }
      if (JavaFeature.STRING_SWITCH.isSufficient(level)) {
        PsiClassType stringType = TypeUtils.getStringType(statement);
        myResult.add(createInfoImpl(stringType, stringType));
      }
    }

    private static @NotNull List<ExpectedTypeInfo> collectFromLabels(@NotNull PsiSwitchBlock statement) {

      List<PsiType> labeledExpressionTypes = new ArrayList<>();
      List<PsiType> labeledPatternsTypes = new ArrayList<>();

      List<ExpectedTypeInfo> result = new ArrayList<>();

      boolean mustBeReference = false;
      PsiCodeBlock body = statement.getBody();
      if (body == null) {
        return result;
      }
      for (PsiStatement psiStatement : body.getStatements()) {
        if (psiStatement instanceof PsiSwitchLabelStatementBase labelStatement) {
          if (labelStatement.isDefaultCase()) {
            continue;
          }
          PsiCaseLabelElementList labelElementList = labelStatement.getCaseLabelElementList();
          if (labelElementList == null) {
            continue;
          }
          for (PsiCaseLabelElement caseLabelElement : labelElementList.getElements()) {
            if (caseLabelElement instanceof PsiExpression expression) {
              PsiType type = expression.getType();
              if (type == null) {
                continue;
              }
              if (type == PsiTypes.nullType()) {
                mustBeReference = true;
                continue;
              }
              labeledExpressionTypes.add(type);
            }
            if (caseLabelElement instanceof PsiPattern pattern) {
              PsiType type = JavaPsiPatternUtil.getPatternType(pattern);
              if (type == null) {
                continue;
              }
              labeledPatternsTypes.add(type);
            }
          }
        }
      }
      result.addAll(findExpectedTypesForExpressions(labeledExpressionTypes, mustBeReference, statement));
      result.addAll(findExpectedTypesForPatterns(labeledPatternsTypes, statement));
      return result;
    }


    private static @NotNull List<ExpectedTypeInfo> findExpectedTypesForPatterns(@NotNull List<PsiType> expectedTypes,
                                                                                @NotNull PsiSwitchBlock statement) {
      PsiManager manager = statement.getManager();
      if(manager == null) return Collections.emptyList();
      PsiType lub = getLeastUpperBound(expectedTypes, manager);
      if (lub == null) return Collections.emptyList();
      return Collections.singletonList(createInfo(lub, ExpectedTypeInfo.TYPE_OR_SUPERTYPE, lub, TailTypes.noneType()));
    }

    private static @Nullable PsiType getLeastUpperBound(@NotNull List<PsiType> types, @NotNull PsiManager manager) {
      if (types.isEmpty()) return null;
      Iterator<PsiType> iterator = types.iterator();
      PsiType accumulator = iterator.next();
      while (iterator.hasNext()) {
        PsiType type = iterator.next();
        accumulator = GenericsUtil.getLeastUpperBound(accumulator, type, manager);
        if (accumulator == null) return null;
      }
      return accumulator;
    }

    private static @NotNull List<ExpectedTypeInfo> findExpectedTypesForExpressions(@NotNull List<PsiType> expectedTypes,
                                                                                   boolean mustBeReference,
                                                                                   @NotNull PsiSwitchBlock context) {
      List<ExpectedTypeInfo> result = new ArrayList<>();
      Set<PsiType> processedTypes = new HashSet<>();
      for (PsiType expectedType : expectedTypes) {
        if (expectedType == null) {
          continue;
        }
        if (expectedType instanceof PsiClassType classType) {
          PsiClass resolved = classType.resolve();
          if (resolved != null && resolved.isEnum()) {
            processedTypes.add(expectedType);
            result.add(createInfoImpl(expectedType, expectedType));
            continue;
          }
        }
        if (expectedType.equalsToText(CommonClassNames.JAVA_LANG_STRING) ||
            TypeConversionUtil.isPrimitiveAndNotNullOrWrapper(expectedType)) {
          if (expectedType instanceof PsiPrimitiveType primitiveType) {
            if (primitiveType.equals(PsiTypes.longType()) ||
                primitiveType.equals(PsiTypes.doubleType()) ||
                primitiveType.equals(PsiTypes.floatType())) {
              return List.of(); //unexpected types, let's suggest default
            }
            if (mustBeReference) {
              expectedType = primitiveType.getBoxedType(context);
              if (expectedType == null) {
                continue;
              }
            } else {
              addWithWrapper(processedTypes, PsiTypes.intType(), result, context);
            }
          }
          addWithWrapper(processedTypes, expectedType, result, context);
          continue;
        }
        return List.of(); //something unexpected, let's suggest default
      }
      return result;
    }

    private static void addWithWrapper(@NotNull Set<PsiType> processedTypes,
                                       @NotNull PsiType expectedType,
                                       @NotNull List<ExpectedTypeInfo> result,
                                       @Nullable PsiElement context) {
      addIfNotExist(processedTypes, expectedType, result, createInfoImpl(expectedType, expectedType));
      if (context!=null && expectedType instanceof PsiPrimitiveType primitiveType) {
        PsiClassType type = primitiveType.getBoxedType(context);
        if (type != null) {
          addIfNotExist(processedTypes, type, result, createInfoImpl(type, type));
        }
      }
    }

    private static void addIfNotExist(@NotNull Set<PsiType> processedTypes,
                                      @NotNull PsiType expectedType,
                                      @NotNull List<ExpectedTypeInfo> result,
                                      @NotNull ExpectedTypeInfo expectedTypeInfo) {
      if (!processedTypes.contains(expectedType)) {
        processedTypes.add(expectedType);
        result.add(expectedTypeInfo);
      }
    }

    @Override
    public void visitSynchronizedStatement(@NotNull PsiSynchronizedStatement statement) {
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(statement.getProject());
      PsiType objectType = factory.createTypeByFQClassName(CommonClassNames.JAVA_LANG_OBJECT, myExpr.getResolveScope());
      myResult.add(createInfoImpl(objectType, objectType));
    }

    @Override
    public void visitVariable(@NotNull PsiVariable variable) {
      if (variable instanceof PsiLocalVariable local && myForCompletion && myHops < MAX_VAR_HOPS) {
        PsiTypeElement typeElement = local.getTypeElement();
        if (typeElement.isInferredType()) {
          myHops++;
          List<PsiReferenceExpression> refs = StreamEx.of(VariableAccessUtils.getVariableReferencesNoCache(variable))
            // Remove invalid refs from initializer/annotations to avoid possible SOE
            .remove(ref -> PsiTreeUtil.isAncestor(variable, ref, true))
            .toList();
          for (PsiReferenceExpression ref : refs) {
            myExpr = ref;
            ref.getParent().accept(this);
            if (myResult.size() >= myMaxCandidates) {
              break;
            }
          }
          if (myResult.size() > 1) {
            Set<ExpectedTypeInfo> distinct = new LinkedHashSet<>(myResult);
            myResult.clear();
            myResult.addAll(distinct);
          }
          if (!myResult.isEmpty()) {
            return;
          }
        }
      }
      PsiType type = variable.getType();
      TailType tail = variable instanceof PsiResourceVariable ? TailTypes.noneType() :
                      PsiUtilCore.getElementType(PsiTreeUtil.nextCodeLeaf(variable)) == JavaTokenType.COMMA ? CommaTailType.INSTANCE :
                      TailTypes.semicolonType();
      myResult.add(createInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, tail, null, getPropertyName(variable)));
    }

    @Override
    public void visitAssignmentExpression(@NotNull PsiAssignmentExpression assignment) {
      if (myExpr.equals(assignment.getRExpression())) {
        PsiExpression lExpr = assignment.getLExpression();
        PsiType type = lExpr.getType();
        if (type != null) {
          TailType tailType = getAssignmentRValueTailType(assignment);
          Supplier<String> expectedName = ExpectedTypeInfoImpl.NULL;
          if (lExpr instanceof PsiReferenceExpression) {
            PsiElement refElement = ((PsiReferenceExpression)lExpr).resolve();
            if (refElement instanceof PsiVariable) {
              expectedName = getPropertyName((PsiVariable)refElement);
            }
          }
          myResult.add(createInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, tailType, null, expectedName));
        }
      }
      else {
        if (myForCompletion) {
          myExpr = (PsiExpression)myExpr.getParent();
          assignment.getParent().accept(this);
          return;
        }

        PsiExpression rExpr = assignment.getRExpression();
        if (rExpr != null) {
          PsiType type = rExpr.getType();
          if (type != null && type != PsiTypes.nullType()) {
            if (type instanceof PsiClassType) {
              final PsiClass resolved = ((PsiClassType)type).resolve();
              if (resolved instanceof PsiAnonymousClass) {
                type = ((PsiAnonymousClass)resolved).getBaseClassType();
              }
            }
            final int kind = assignment.getOperationTokenType() != JavaTokenType.EQ
                             ? ExpectedTypeInfo.TYPE_STRICTLY
                             : ExpectedTypeInfo.TYPE_OR_SUPERTYPE;
            myResult.add(createInfoImpl(type, kind, type, TailTypes.noneType()));
          }
        }
      }
    }

    private static @NotNull TailType getAssignmentRValueTailType(@NotNull PsiAssignmentExpression assignment) {
      if (assignment.getParent() instanceof PsiExpressionStatement) {
        if (!(assignment.getParent().getParent() instanceof PsiForStatement forStatement)) {
          return TailTypes.semicolonType();
        }

        if (!assignment.getParent().equals(forStatement.getUpdate())) {
          return TailTypes.semicolonType();
        }
      }
      return TailTypes.noneType();
    }

    @Override
    public void visitExpressionList(@NotNull PsiExpressionList list) {
      PsiResolveHelper helper = JavaPsiFacade.getInstance(list.getProject()).getResolveHelper();
      PsiElement parent = list.getParent();
      if (parent instanceof PsiMethodCallExpression methodCall) {
        CandidateInfo[] candidates = helper.getReferencedMethodCandidates(methodCall, false, true);
        Collections.addAll(myResult, getExpectedArgumentTypesForMethodCall(candidates, list, myExpr, myForCompletion));
      }
      else if (parent instanceof PsiEnumConstant enumConstant) {
        getExpectedArgumentsTypesForEnumConstant(enumConstant, list);
      }
      else if (parent instanceof PsiNewExpression newExpression) {
        getExpectedArgumentsTypesForNewExpression(newExpression, list);
      }
      else if (parent instanceof PsiAnonymousClass) {
        getExpectedArgumentsTypesForNewExpression((PsiNewExpression)parent.getParent(), list);
      }
      else if (parent instanceof PsiSwitchLabelStatementBase switchLabel) {
        PsiSwitchBlock switchBlock = switchLabel.getEnclosingSwitchBlock();
        handleCaseElementList(switchBlock);
      }
    }

    private void handleCaseElementList(PsiSwitchBlock switchBlock) {
      if (switchBlock != null) {
        PsiExpression expression = switchBlock.getExpression();
        if (expression != null) {
          PsiType type = expression.getType();
          if (type != null) {
            myResult.add(createInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, JavaTailTypes.forSwitchLabel(switchBlock)));
          }
        }
      }
    }

    @Override
    public void visitCaseLabelElementList(@NotNull PsiCaseLabelElementList list) {
      PsiElement parent = list.getParent();
      if (parent instanceof PsiSwitchLabelStatementBase) {
        PsiSwitchBlock switchBlock = ((PsiSwitchLabelStatementBase)parent).getEnclosingSwitchBlock();
        handleCaseElementList(switchBlock);
      }
    }

    private void getExpectedArgumentsTypesForEnumConstant(@NotNull PsiEnumConstant enumConstant, @NotNull PsiExpressionList list) {
      final PsiClass aClass = enumConstant.getContainingClass();
      if (aClass != null) {
        LOG.assertTrue(aClass.isEnum());
        getExpectedTypesForConstructorCall(aClass, list, PsiSubstitutor.EMPTY);
      }
    }

    private void getExpectedArgumentsTypesForNewExpression(@NotNull PsiNewExpression newExpr, @NotNull PsiExpressionList list) {
      if (PsiDiamondType.hasDiamond(newExpr)) {
        final List<CandidateInfo> candidates = PsiDiamondTypeImpl.collectStaticFactories(newExpr);
        if (candidates != null) {
          final PsiExpressionList argumentList = Objects.requireNonNull(newExpr.getArgumentList());
          Collections.addAll(myResult, getExpectedArgumentTypesForMethodCall(candidates.toArray(CandidateInfo.EMPTY_ARRAY), argumentList, myExpr, myForCompletion));
        }
        return;
      }
      PsiType newType = newExpr.getType();
      if (newType instanceof PsiClassType) {
        JavaResolveResult resolveResult = PsiUtil.resolveGenericsClassInType(newType);
        PsiClass newClass = (PsiClass)resolveResult.getElement();
        final PsiSubstitutor substitutor;
        if (newClass instanceof PsiAnonymousClass anonymous) {
          newClass = anonymous.getBaseClassType().resolve();
          if (newClass == null) return;

          substitutor = TypeConversionUtil.getSuperClassSubstitutor(newClass, anonymous, PsiSubstitutor.EMPTY);
        } else if (newClass != null) {
          substitutor = resolveResult.getSubstitutor();
        }
        else {
          return;
        }
        getExpectedTypesForConstructorCall(newClass, list, substitutor);
      }
    }

    private void getExpectedTypesForConstructorCall(@NotNull PsiClass referencedClass,
                                                    @NotNull PsiExpressionList argumentList,
                                                    @NotNull PsiSubstitutor substitutor) {
      List<CandidateInfo> array = new ArrayList<>();
      for (PsiMethod constructor : referencedClass.getConstructors()) {
        array.add(new MethodCandidateInfo(constructor, substitutor, false, false, argumentList, null, argumentList.getExpressionTypes(), null));
      }
      CandidateInfo[] candidates = array.toArray(CandidateInfo.EMPTY_ARRAY);
      Collections.addAll(myResult, getExpectedArgumentTypesForMethodCall(candidates, argumentList, myExpr, myForCompletion));
    }

    @Override
    public void visitPolyadicExpression(@NotNull PsiPolyadicExpression expr) {
      PsiExpression[] operands = expr.getOperands();
      final int index = Arrays.asList(operands).indexOf(myExpr);
      if (index < 0) return; // broken syntax

      IElementType op = expr.getOperationTokenType();
      PsiExpression anotherExpr = index > 0 ? operands[0] : 1 < operands.length ? operands[1] : null;

      if (myForCompletion && index == 0) {
        if (op == JavaTokenType.EQEQ || op == JavaTokenType.NE) {
          ContainerUtil.addIfNotNull(myResult, getEqualsType(anotherExpr));
        }
        if (op == JavaTokenType.LT || op == JavaTokenType.LE ||
            op == JavaTokenType.GT || op == JavaTokenType.GE) {
          PsiType anotherType = anotherExpr != null ? anotherExpr.getType() : null;
          if (anotherType != null) {
            myResult.add(createInfoImpl(PsiTypes.doubleType(), anotherType));
          }
        }
        final MyParentVisitor visitor = new MyParentVisitor(expr, true, myClassProvider, myVoidable, myUsedAfter, myMaxCandidates);
        myExpr = (PsiExpression)myExpr.getParent();
        expr.getParent().accept(visitor);
        myResult.addAll(visitor.myResult);
        if (!(expr.getParent() instanceof PsiExpressionList)) {
          myResult.replaceAll(
            info -> createInfoImpl(info.getType(), info.getKind(), info.getDefaultType(), TailTypes.noneType(), info.getCalledMethod(),
                                   ((ExpectedTypeInfoImpl)info)::getExpectedName
            ));
        }
        return;
      }

      PsiType anotherType = anotherExpr != null ? anotherExpr.getType() : null;

      if (op == JavaTokenType.MINUS ||
          op == JavaTokenType.ASTERISK ||
          op == JavaTokenType.DIV ||
          op == JavaTokenType.PERC ||
          op == JavaTokenType.LT ||
          op == JavaTokenType.GT ||
          op == JavaTokenType.LE ||
          op == JavaTokenType.GE) {
        if (anotherType != null) {
          myResult.add(createInfoImpl(PsiTypes.doubleType(), anotherType));
        }
      }
      else if (op == JavaTokenType.PLUS) {
        if (anotherType == null || anotherType.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
          PsiClassType objectType = PsiType.getJavaLangObject(expr.getManager(), expr.getResolveScope());
          myResult.add(createInfoImpl(objectType, anotherType != null ? anotherType : objectType));
        }
        else if (PsiTypes.doubleType().isAssignableFrom(anotherType)) {
          myResult.add(createInfoImpl(PsiTypes.doubleType(), anotherType));
        }
      }
      else if (op == JavaTokenType.EQEQ || op == JavaTokenType.NE) {
        ContainerUtil.addIfNotNull(myResult, getEqualsType(anotherExpr));
      }
      else if (op == JavaTokenType.LTLT || op == JavaTokenType.GTGT || op == JavaTokenType.GTGTGT) {
        if (anotherType != null) {
          myResult.add(createInfoImpl(PsiTypes.longType(), ExpectedTypeInfo.TYPE_BETWEEN, PsiTypes.shortType(), TailTypes.noneType()));
        }
      }
      else if (op == JavaTokenType.OROR || op == JavaTokenType.ANDAND) {
        myResult.add(createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(), TailTypes.noneType()));
      }
      else if (op == JavaTokenType.OR || op == JavaTokenType.XOR || op == JavaTokenType.AND) {
        if (anotherType != null) {
          ExpectedTypeInfoImpl info;
          if (PsiTypes.booleanType().equals(anotherType)) {
            info = createInfoImpl(anotherType, ExpectedTypeInfo.TYPE_STRICTLY, anotherType, TailTypes.noneType());
          }
          else {
            info = createInfoImpl(PsiTypes.longType(), anotherType);
          }
          myResult.add(info);
        }
      }
    }

    private static @Nullable ExpectedTypeInfo getEqualsType(@Nullable PsiExpression anotherExpr) {
      PsiType anotherType = anotherExpr != null ? anotherExpr.getType() : null;
      if (anotherType == null) {
        return null;
      }

      Supplier<String> expectedName = ExpectedTypeInfoImpl.NULL;
      if (anotherExpr instanceof PsiReferenceExpression) {
        PsiElement refElement = ((PsiReferenceExpression)anotherExpr).resolve();
        if (refElement instanceof PsiVariable) {
          expectedName = getPropertyName((PsiVariable)refElement);
        }
      }
      ExpectedTypeInfoImpl info;
      if (anotherType instanceof PsiPrimitiveType) {
        if (PsiTypes.booleanType().equals(anotherType)) {
          info = createInfoImpl(anotherType, ExpectedTypeInfo.TYPE_STRICTLY, anotherType, TailTypes.noneType(), null, expectedName);
        }
        else if (PsiTypes.nullType().equals(anotherType)) {
          PsiType objectType = PsiType.getJavaLangObject(anotherExpr.getManager(), anotherExpr.getResolveScope());
          info = createInfoImpl(objectType, ExpectedTypeInfo.TYPE_OR_SUBTYPE, objectType, TailTypes.noneType(), null, expectedName);
        }
        else {
          info = createInfoImpl(PsiTypes.doubleType(), ExpectedTypeInfo.TYPE_OR_SUBTYPE, anotherType, TailTypes.noneType(), null, expectedName);
        }
      }
      else {
        info = createInfoImpl(anotherType, ExpectedTypeInfo.TYPE_STRICTLY, anotherType, TailTypes.noneType(), null, expectedName);
      }

      return info;
    }

    @Override
    public void visitPrefixExpression(@NotNull PsiPrefixExpression expr) {
      IElementType i = expr.getOperationTokenType();
      final PsiType type = expr.getType();
      final PsiElement parent = expr.getParent();
      final TailType tailType = parent instanceof PsiAssignmentExpression && ((PsiAssignmentExpression)parent).getRExpression() == expr ?
                                getAssignmentRValueTailType((PsiAssignmentExpression)parent) :
                                TailTypes.noneType();
      if (i == JavaTokenType.PLUSPLUS || i == JavaTokenType.MINUSMINUS || i == JavaTokenType.TILDE) {
        ExpectedTypeInfoImpl info;
        if (myUsedAfter && type != null) {
          info = createInfoImpl(type, ExpectedTypeInfo.TYPE_STRICTLY, type, tailType);
        }
        else {
          if (type != null) {
            info = createInfoImpl(type, type instanceof PsiPrimitiveType ? ExpectedTypeInfo.TYPE_OR_SUPERTYPE : ExpectedTypeInfo.TYPE_OR_SUBTYPE,
                                  PsiTypes.intType(), tailType);
          }
          else {
            info = createInfoImpl(PsiTypes.longType(), ExpectedTypeInfo.TYPE_OR_SUBTYPE, PsiTypes.intType(), tailType);
          }
        }
        myResult.add(info);
      }
      else if (i == JavaTokenType.PLUS || i == JavaTokenType.MINUS) {
        if (parent instanceof PsiStatement) {
          myResult.add(createInfoImpl(PsiTypes.doubleType(), ExpectedTypeInfo.TYPE_OR_SUBTYPE, PsiTypes.intType(), tailType));
        }
        else {
          myExpr = (PsiExpression)myExpr.getParent();
          parent.accept(this);
        }
      }
      else if (i == JavaTokenType.EXCL) {
        myResult.add(createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(), tailType));
      }
    }

    @Override
    public void visitPostfixExpression(@NotNull PsiPostfixExpression expr) {
      if (myForCompletion) return;
      PsiType type = expr.getType();
      ExpectedTypeInfoImpl info;
      if (myUsedAfter && type != null) {
        info = createInfoImpl(type, ExpectedTypeInfo.TYPE_STRICTLY, type, TailTypes.noneType());
      }
      else {
        if (type != null) {
          info = createInfoImpl(type, type instanceof PsiPrimitiveType ? ExpectedTypeInfo.TYPE_OR_SUPERTYPE : ExpectedTypeInfo.TYPE_OR_SUBTYPE,
                                PsiTypes.intType(), TailTypes.noneType());
        }
        else {
          info = createInfoImpl(PsiTypes.longType(), PsiTypes.intType());
        }
      }
      myResult.add(info);
    }

    @Override
    public void visitArrayInitializerExpression(@NotNull PsiArrayInitializerExpression expr) {
      PsiElement pParent = expr.getParent();
      PsiType arrayType = null;
      if (pParent instanceof PsiVariable) {
        arrayType = ((PsiVariable)pParent).getType();
      }
      else if (pParent instanceof PsiNewExpression) {
        arrayType = ((PsiNewExpression)pParent).getType();
      }
      else if (pParent instanceof PsiArrayInitializerExpression) {
        PsiType type = ((PsiArrayInitializerExpression)pParent).getType();
        if (type instanceof PsiArrayType) {
          arrayType = ((PsiArrayType)type).getComponentType();
        }
      }

      if (arrayType instanceof PsiArrayType) {
        PsiType componentType = ((PsiArrayType)arrayType).getComponentType();
        myResult.add(createInfoImpl(componentType, componentType));
      }
    }

    @Override
    public void visitNewExpression(@NotNull PsiNewExpression expression) {
      PsiExpression[] arrayDimensions = expression.getArrayDimensions();
      for (PsiExpression dimension : arrayDimensions) {
        if (myExpr.equals(dimension)) {
          myResult.add(createInfoImpl(PsiTypes.intType(), PsiTypes.intType()));
          return;
        }
      }
    }

    @Override
    public void visitArrayAccessExpression(@NotNull PsiArrayAccessExpression expr) {
      if (myExpr.equals(expr.getIndexExpression())) {
        myResult.add(createInfoImpl(PsiTypes.intType(), PsiTypes.intType()));
      }
      else if (myExpr.equals(expr.getArrayExpression())) {
        if (myForCompletion) {
          myExpr = (PsiExpression)myExpr.getParent();
          expr.getParent().accept(this);
          return;
        }

        PsiElement parent = expr.getParent();
        MyParentVisitor visitor = new MyParentVisitor(expr, false, myClassProvider, myVoidable, myUsedAfter, myMaxCandidates);
        myExpr = (PsiExpression)myExpr.getParent();
        parent.accept(visitor);
        ExpectedTypeInfo[] componentTypeInfo = visitor.getResult();
        if (componentTypeInfo.length == 0) {
          myResult.addAll(anyArrayType());
        }
        else {
          for (ExpectedTypeInfo compInfo : componentTypeInfo) {
            PsiType expectedArrayType = compInfo.getType().createArrayType();
            myResult.add(createInfoImpl(expectedArrayType, expectedArrayType));
          }
        }
      }
    }

    @Override
    public void visitConditionalExpression(@NotNull PsiConditionalExpression expr) {
      if (myExpr.equals(expr.getCondition())) {
        if (myForCompletion) {
          myExpr = expr;
          myExpr.getParent().accept(this);
          return;
        }

        myResult.add(createInfoImpl(PsiTypes.booleanType(), ExpectedTypeInfo.TYPE_STRICTLY, PsiTypes.booleanType(), TailTypes.noneType()));
      }
      else if (myExpr.equals(expr.getThenExpression())) {
        ExpectedTypeInfo[] types = getExpectedTypes(expr, myForCompletion, ourGlobalScopeClassProvider, false, false, myMaxCandidates);
        for (int i = 0; i < types.length; i++) {
          final ExpectedTypeInfo info = types[i];
          types[i] =
            createInfoImpl(info.getType(), info.getKind(), info.getDefaultType(), TailTypes.conditionalExpressionColonType(), info.getCalledMethod(),
                           ((ExpectedTypeInfoImpl)info)::getExpectedName);
        }
        Collections.addAll(myResult, types);
      }
      else {
        if (!myExpr.equals(expr.getElseExpression())) {
          LOG.error(Arrays.asList(expr.getChildren()) + "; " + myExpr);
        }
        Collections.addAll(myResult, getExpectedTypes(expr, myForCompletion, ourGlobalScopeClassProvider, false, false, myMaxCandidates));
      }
    }

    @Override
    public void visitThrowStatement(@NotNull PsiThrowStatement statement) {
      if (statement.getException() == myExpr) {
        PsiManager manager = statement.getManager();
        PsiType throwableType = JavaPsiFacade.getElementFactory(manager.getProject()).createTypeByFQClassName(CommonClassNames.JAVA_LANG_THROWABLE, myExpr.getResolveScope());
        PsiElement container = PsiTreeUtil.getParentOfType(statement, PsiMethod.class, PsiLambdaExpression.class, PsiClass.class);
        PsiType[] throwsTypes = PsiType.EMPTY_ARRAY;
        if (container instanceof PsiMethod) {
          throwsTypes = ((PsiMethod)container).getThrowsList().getReferencedTypes();
        }
        else if (container instanceof PsiLambdaExpression) {
          final PsiMethod method = LambdaUtil.getFunctionalInterfaceMethod(container);
          if (method != null) {
            throwsTypes = method.getThrowsList().getReferencedTypes();
          }
        }

        if (throwsTypes.length == 0) {
          final PsiClassType exceptionType = JavaPsiFacade.getElementFactory(manager.getProject()).createTypeByFQClassName("java.lang.Exception", myExpr.getResolveScope());
          throwsTypes = new PsiClassType[]{exceptionType};
        }

        for (PsiType throwsType : throwsTypes) {
          myResult.add(createInfoImpl(
            myExpr instanceof PsiTypeCastExpression && myForCompletion ? throwsType : throwableType,
            ExpectedTypeInfo.TYPE_OR_SUBTYPE,
            throwsType,
            TailTypes.semicolonType()
          ));
        }
      }
    }

    @Override
    public void visitCodeFragment(@NotNull JavaCodeFragment codeFragment) {
      if (codeFragment instanceof PsiExpressionCodeFragment) {
        final PsiType type = ((PsiExpressionCodeFragment)codeFragment).getExpectedType();
        if (type != null) {
          myResult.add(createInfoImpl(type, type));
        }
      }
    }

    private ExpectedTypeInfo @NotNull [] getExpectedArgumentTypesForMethodCall(CandidateInfo @NotNull [] allCandidates,
                                                                               @NotNull PsiExpressionList argumentList,
                                                                               @NotNull PsiExpression argument,
                                                                               boolean forCompletion) {
      if (allCandidates.length == 0) {
        return ExpectedTypeInfo.EMPTY_ARRAY;
      }

      if (CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION) {
        allCandidates = selectCandidateChosenOnCompletion(argumentList.getParent(), allCandidates);
      }

      PsiMethod toExclude = JavaPsiConstructorUtil.isConstructorCall(argumentList.getParent())
                            ? PsiTreeUtil.getParentOfType(argument, PsiMethod.class) : null;

      PsiResolveHelper helper = JavaPsiFacade.getInstance(myExpr.getProject()).getResolveHelper();
      List<CandidateInfo> methodCandidates = new ArrayList<>();
      for (CandidateInfo candidate : allCandidates) {
        PsiElement element = candidate.getElement();
        if (element instanceof PsiMethod && helper.isAccessible((PsiMember)element, argumentList, null) && element != toExclude) {
          methodCandidates.add(candidate);
        }
      }
      if (methodCandidates.isEmpty()) {
        Collections.addAll(methodCandidates, allCandidates);
      }

      final PsiExpression[] args = argumentList.getExpressions().clone();
      final int index = ArrayUtil.indexOf(args, argument);
      LOG.assertTrue(index >= 0);

      final PsiExpression[] leftArgs;
      if (index <= args.length - 1) {
        leftArgs = Arrays.copyOf(args, index);
        if (forCompletion) {
          args[index] = null;
        }
      }
      else {
        leftArgs = null;
      }

      ParameterTypeInferencePolicy policy = forCompletion ? CompletionParameterTypeInferencePolicy.INSTANCE : DefaultParameterTypeInferencePolicy.INSTANCE;

      Set<ExpectedTypeInfo> set = new LinkedHashSet<>();
      for (CandidateInfo candidateInfo : methodCandidates) {
        PsiMethod method = (PsiMethod)candidateInfo.getElement();
        PsiTypeParameter returnTypeParameter = getReturnTypeParameterNotMentionedPreviously(index, method);
        PsiSubstitutor substitutor;
        if (candidateInfo instanceof MethodCandidateInfo info) {
          substitutor = info.inferSubstitutorFromArgs(policy, args);
          if (!info.isStaticsScopeCorrect() && !method.hasModifierProperty(PsiModifier.STATIC) || info.getInferenceErrorMessage() != null) continue;
          if (forCompletion && returnTypeParameter != null) {
            PsiType substituted = substitutor.substitute(returnTypeParameter);
            if (substituted instanceof PsiClassType) {
              // Relax return type substitution
              substitutor = substitutor.put(returnTypeParameter, PsiWildcardType.createExtends(method.getManager(), substituted));
            }
          }
        }
        else {
          substitutor = candidateInfo.getSubstitutor();
        }
        if (substitutor == null) {
          return ExpectedTypeInfo.EMPTY_ARRAY;
        }
        inferMethodCallArgumentTypes(argument, forCompletion, args, index, method, substitutor, set);
        if (set.size() >= myMaxCandidates) break;

        if (leftArgs != null && candidateInfo instanceof MethodCandidateInfo) {
          substitutor = ((MethodCandidateInfo)candidateInfo).inferSubstitutorFromArgs(policy, leftArgs);
          if (substitutor != null) {
            inferMethodCallArgumentTypes(argument, forCompletion, leftArgs, index, method, substitutor, set);
            if (set.size() >= myMaxCandidates) break;
          }
        }
      }

      // try to find some variants without considering previous argument PRIMITIVE_TYPES
      if (forCompletion && set.isEmpty()) {
        for (CandidateInfo candidate : methodCandidates) {
          PsiMethod method = (PsiMethod)candidate.getElement();
          PsiSubstitutor substitutor = candidate.getSubstitutor();
          PsiParameter[] params = method.getParameterList().getParameters();
          if (params.length <= index) continue;
          PsiParameter param = params[index];
          PsiType paramType = getParameterType(param, substitutor);
          if (method.hasTypeParameters() && PsiTypesUtil.mentionsTypeParameters(paramType, ContainerUtil.newHashSet(method.getTypeParameters()))) {
            continue;
          }
          TailType tailType = getMethodArgumentTailType(argument, index, method, substitutor, params);
          ExpectedTypeInfoImpl info = createInfoImpl(paramType, ExpectedTypeInfo.TYPE_OR_SUBTYPE, paramType,
                                                     tailType, method, getPropertyName(param));
          set.add(info);
          if (set.size() >= myMaxCandidates) break;
        }
      }

      return set.toArray(ExpectedTypeInfo.EMPTY_ARRAY);
    }

    private static @Nullable PsiTypeParameter getReturnTypeParameterNotMentionedPreviously(int index, PsiMethod method) {
      PsiTypeParameter returnTypeParameter = ObjectUtils.tryCast(PsiUtil.resolveClassInClassTypeOnly(method.getReturnType()), PsiTypeParameter.class);
      if (returnTypeParameter == null || returnTypeParameter.getOwner() != method) return null;
      PsiParameter[] parameters = method.getParameterList().getParameters();
      for (int i = 0; i < index && i < parameters.length; i++) {
        PsiType prevParameterType = parameters[i].getType();
        if (PsiTypesUtil.mentionsTypeParameters(prevParameterType, Set.of(returnTypeParameter))) return null;
      }
      return returnTypeParameter;
    }

    private static CandidateInfo @NotNull [] selectCandidateChosenOnCompletion(@Nullable PsiElement call, CandidateInfo @NotNull [] candidates) {
      if (call instanceof PsiCall) {
        PsiCall originalCall = CompletionUtil.getOriginalElement((PsiCall)call);
        if (originalCall != null) {
          PsiMethod method = CompletionMemory.getChosenMethod(originalCall);
          if (method != null) {
            for (CandidateInfo candidate : candidates) {
              if (CompletionUtil.getOriginalOrSelf(candidate.getElement()) == method) return new CandidateInfo[]{candidate};
            }
          }
        }
      }
      return candidates;
    }

    private static @NotNull TailType getMethodArgumentTailType(@NotNull PsiExpression argument,
                                                               int index,
                                                               @NotNull PsiMethod method,
                                                               @NotNull PsiSubstitutor substitutor,
                                                               PsiParameter @NotNull [] params) {
      if (index >= params.length || index == params.length - 2 && params[index + 1].isVarArgs()) {
        return TailTypes.noneType();
      }
      if (index == params.length - 1) {
        final PsiElement call = argument.getParent().getParent();
        // ignore JspMethodCall
        if (call instanceof SyntheticElement) return TailTypes.noneType();

        PsiType returnType = method.getReturnType();
        if (returnType != null) returnType = substitutor.substitute(returnType);
        return getFinalCallParameterTailType(call, returnType, method);
      }
      if (CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION) {
        PsiCall completedOuterCall = getCompletedOuterCall(argument);
        if (completedOuterCall != null) return new CommaTailTypeWithSyncHintUpdate(completedOuterCall);
      }
      return CommaTailType.INSTANCE;
    }

    private static @Nullable PsiCall getCompletedOuterCall(@NotNull PsiExpression argument) {
      PsiElement expressionList = argument.getParent();
      if (expressionList instanceof PsiExpressionList) {
        PsiElement call = expressionList.getParent();
        if (call instanceof PsiCall) {
          PsiCall originalCall = CompletionUtil.getOriginalElement((PsiCall)call);
          if (originalCall != null && JavaMethodCallElement.isCompletionMode(originalCall)) {
            return originalCall;
          }
        }
      }
      return null;
    }

    private static void inferMethodCallArgumentTypes(@NotNull PsiExpression argument,
                                                     boolean forCompletion,
                                                     PsiExpression @NotNull [] args,
                                                     int index,
                                                     @NotNull PsiMethod method,
                                                     @NotNull PsiSubstitutor substitutor,
                                                     @NotNull Set<? super ExpectedTypeInfo> array) {
      LOG.assertTrue(substitutor.isValid());
      PsiParameter[] parameters = method.getParameterList().getParameters();
      if (!forCompletion && parameters.length != args.length && !method.isVarArgs()) return;
      if (parameters.length <= index && !method.isVarArgs()) return;

      for (int j = 0; j < index; j++) {
        PsiType paramType = getParameterType(parameters[Math.min(parameters.length - 1, j)], substitutor);
        PsiType argType = args[j].getType();
        if (argType != null && !paramType.isAssignableFrom(argType)) return;
      }
      PsiParameter parameter = parameters[Math.min(parameters.length - 1, index)];
      PsiType parameterType = getParameterType(parameter, substitutor);

      TailType tailType = getMethodArgumentTailType(argument, index, method, substitutor, parameters);
      PsiType defaultType = getDefaultType(method, substitutor, parameterType, argument, args, index);

      NullableComputable<String> propertyName = getPropertyName(parameter);
      ExpectedTypeInfoImpl info = createInfoImpl(parameterType, ExpectedTypeInfo.TYPE_OR_SUBTYPE, defaultType, tailType, method,
                                                 propertyName);
      array.add(info);

      if (index == parameters.length - 1 && parameter.isVarArgs()) {
        //Then we may still want to call with array argument
        final PsiArrayType arrayType = parameterType.createArrayType();
        ExpectedTypeInfoImpl info1 = createInfoImpl(arrayType, ExpectedTypeInfo.TYPE_OR_SUBTYPE, arrayType, tailType, method, propertyName);
        array.add(info1);
      }
    }

    private static @Nullable PsiType getDefaultType(@NotNull PsiMethod method, PsiSubstitutor substitutor, @NotNull PsiType parameterType,
                                                    @NotNull PsiExpression argument, @Nullable PsiExpression @NotNull [] args, int index) {
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass == null) return parameterType;

      PsiType hardcoded = HardcodedDefaultTypesKt.getDefaultType(method, substitutor, index, argument);
      if (hardcoded != null) return hardcoded;

      final PsiElementFactory factory = JavaPsiFacade.getElementFactory(containingClass.getProject());
      PsiMethodCallExpression call = ObjectUtils.tryCast(argument.getParent().getParent(), PsiMethodCallExpression.class);
      AssertHint assertHint = AssertHint.createAssertEqualsLikeHintForCompletion(call, args, method, index);
      if (assertHint != null) {
        PsiExpression other = assertHint.getOtherExpression(argument);
        if (other != null) {
          ExpectedTypeInfo info = getEqualsType(other);
          if (info != null && parameterType.isAssignableFrom(info.getDefaultType())) {
            return info.getDefaultType();
          }
        }
      }
      String className = containingClass.getName();
      if (className != null && className.startsWith("Log")) {
        if (parameterType instanceof PsiClassType) {
          PsiType typeArg = PsiUtil.substituteTypeParameter(parameterType, CommonClassNames.JAVA_LANG_CLASS, 0, true);
          if (typeArg instanceof PsiWildcardType && !((PsiWildcardType)typeArg).isBounded() ||
              typeArg != null && TypeConversionUtil.erasure(typeArg).equalsToText(CommonClassNames.JAVA_LANG_OBJECT)) {
            PsiClass placeClass = PsiTreeUtil.getContextOfType(argument, PsiClass.class);
            PsiClass classClass = ((PsiClassType)parameterType).resolve();
            if (placeClass != null && classClass != null && classClass.getTypeParameters().length == 1) {
              return factory.createType(classClass, factory.createType(placeClass));
            }
          }
        }
      }
      return parameterType;
    }

    private static PsiType getParameterType(@NotNull PsiParameter parameter, @NotNull PsiSubstitutor substitutor) {
      PsiType type = parameter.getType();
      LOG.assertTrue(type.isValid());
      if (parameter.isVarArgs()) {
        if (type instanceof PsiArrayType) {
          type = ((PsiArrayType)type).getComponentType();
        }
        else {
          LOG.error("Vararg parameter with non-array type. Class=" + parameter.getClass() + "; type=" + parameter.getType());
        }
      }
      PsiType parameterType = GenericsUtil.eliminateExtendsFinalWildcard(substitutor.substitute(type));
      if (parameterType instanceof PsiCapturedWildcardType) {
        parameterType = ((PsiCapturedWildcardType)parameterType).getWildcard();
      }
      if (parameterType instanceof PsiWildcardType) {
        final PsiType bound = ((PsiWildcardType)parameterType).getBound();
        return bound != null ? bound : PsiType.getJavaLangObject(parameter.getManager(), GlobalSearchScope.allScope(parameter.getProject()));
      }
      return parameterType;
    }

    private static @NotNull NullableComputable<String> getPropertyName(@NotNull PsiVariable variable) {
      return () -> {
        final String name = variable.getName();
        if (name == null) return null;
        JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(variable.getProject());
        VariableKind variableKind = codeStyleManager.getVariableKind(variable);
        return codeStyleManager.variableNameToPropertyName(name, variableKind);
      };
    }

    private @NotNull List<ExpectedTypeInfo> anyArrayType() {
      PsiType objType = PsiType.getJavaLangObject(myExpr.getManager(), myExpr.getResolveScope()).createArrayType();
      ExpectedTypeInfo info = createInfoImpl(objType, objType);
      ExpectedTypeInfo info1 = createInfoImpl(PsiTypes.doubleType().createArrayType(), PsiTypes.intType().createArrayType());
      PsiType booleanType = PsiTypes.booleanType().createArrayType();
      ExpectedTypeInfo info2 = createInfoImpl(booleanType, ExpectedTypeInfo.TYPE_STRICTLY, booleanType, TailTypes.noneType());
      return Arrays.asList(info, info1, info2);
    }

    private ExpectedTypeInfo @NotNull [] findClassesWithDeclaredMethod(@NotNull PsiMethodCallExpression methodCallExpr) {
      PsiUtilCore.ensureValid(methodCallExpr);
      final PsiReferenceExpression reference = methodCallExpr.getMethodExpression();
      if (reference.getQualifierExpression() instanceof PsiClassObjectAccessExpression) {
        return ExpectedTypeInfo.EMPTY_ARRAY;
      }
      final PsiManager manager = methodCallExpr.getManager();
      final JavaPsiFacade facade = JavaPsiFacade.getInstance(manager.getProject());
      Set<PsiMethod> psiMethods = mapToDeepestSuperMethods(myClassProvider.findDeclaredMethods(manager, Objects.requireNonNull(reference.getReferenceName())));
      Set<ExpectedTypeInfo> types = new HashSet<>();
      for (PsiMethod method : psiMethods) {
        final PsiClass aClass = method.getContainingClass();
        if (aClass == null || !facade.getResolveHelper().isAccessible(method, reference, aClass)) continue;

        final PsiSubstitutor substitutor = ExpectedTypeUtil.inferSubstitutor(method, methodCallExpr, false);
        final PsiClassType type =
          substitutor == null ? facade.getElementFactory().createType(aClass) : facade.getElementFactory().createType(aClass, substitutor);

        if (method.hasModifierProperty(PsiModifier.STATIC) || method.hasModifierProperty(PsiModifier.PRIVATE)) {
          types.add(createInfoImpl(type, ExpectedTypeInfo.TYPE_STRICTLY, type, TailTypes.dotType()));
        }
        else {
          types.add(createInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, TailTypes.dotType()));
        }
      }

      return types.toArray(ExpectedTypeInfo.EMPTY_ARRAY);
    }

    private static Set<PsiMethod> mapToDeepestSuperMethods(PsiMethod[] methods) {
      LinkedHashSet<PsiMethod> psiMethods = new LinkedHashSet<>();
      for (PsiMethod m : methods) {
        if (m.hasModifierProperty(PsiModifier.STATIC) || m.hasModifierProperty(PsiModifier.PRIVATE)) {
          psiMethods.add(m);
        }
        else {
          PsiMethod[] superMethods = m.findDeepestSuperMethods();
          if (superMethods.length > 0) {
            psiMethods.addAll(Arrays.asList(superMethods));
          }
          else {
            psiMethods.add(m);
          }
        }
      }
      return psiMethods;
    }

    private ExpectedTypeInfo @NotNull [] findClassesWithDeclaredField(@NotNull PsiReferenceExpression expression) {
      final JavaPsiFacade facade = JavaPsiFacade.getInstance(expression.getProject());
      PsiField[] fields = myClassProvider.findDeclaredFields(expression.getManager(), Objects.requireNonNull(expression.getReferenceName()));
      List<ExpectedTypeInfo> types = new ArrayList<>();
      for (PsiField field : fields) {
        final PsiClass aClass = field.getContainingClass();
        if (aClass == null || !facade.getResolveHelper().isAccessible(field, expression, aClass)) continue;

        final PsiType type = facade.getElementFactory().createType(aClass);

        int kind = field.hasModifierProperty(PsiModifier.STATIC) ||
                   field.hasModifierProperty(PsiModifier.FINAL) ||
                   field.hasModifierProperty(PsiModifier.PRIVATE)
                   ? ExpectedTypeInfo.TYPE_STRICTLY
                   : ExpectedTypeInfo.TYPE_OR_SUBTYPE;
        ExpectedTypeInfo info = createInfoImpl(type, kind, type, TailTypes.dotType());
        //Do not filter inheritors!
        types.add(info);
      }
      return types.toArray(ExpectedTypeInfo.EMPTY_ARRAY);
    }
  }

  /**
   * Finds fields and methods of specified name whenever corresponding reference has been encountered.
   * By default searches in the global scope (see ourGlobalScopeClassProvider), but caller can provide its own algorithm e.g. to narrow search scope
   */
  public interface ExpectedClassProvider {
    PsiField @NotNull [] findDeclaredFields(@NotNull PsiManager manager, @NotNull String name);

    PsiMethod @NotNull [] findDeclaredMethods(@NotNull PsiManager manager, @NotNull String name);
  }

  public static @NotNull TailType getFinalCallParameterTailType(@NotNull PsiElement call, @Nullable PsiType returnType, @NotNull PsiMethod method) {
    if (method.isConstructor() &&
        call instanceof PsiMethodCallExpression && ((PsiMethodCallExpression)call).getMethodExpression() instanceof PsiSuperExpression) {
      return JavaTailTypes.CALL_RPARENTH_SEMICOLON;
    }

    final boolean chainable = !PsiTypes.voidType().equals(returnType) && returnType != null || method.isConstructor() && call instanceof PsiNewExpression;

    final PsiElement parent = call.getParent();
    final boolean statementContext = parent instanceof PsiExpressionStatement || parent instanceof PsiVariable ||
                                     parent instanceof PsiCodeBlock;

    if (parent instanceof PsiThrowStatement || statementContext && !chainable) {
      return JavaTailTypes.CALL_RPARENTH_SEMICOLON;
    }

    return JavaTailTypes.CALL_RPARENTH;
  }

  private static final class CommaTailTypeWithSyncHintUpdate extends TailType {
    private final PsiCall myOriginalCall;

    private CommaTailTypeWithSyncHintUpdate(@NotNull PsiCall originalCall) {myOriginalCall = originalCall;}

    @Override
    public boolean isApplicable(@NotNull InsertionContext context) {
      return CommaTailType.INSTANCE.isApplicable(context);
    }

    @Override
    public int processTail(Editor editor, int tailOffset) {
      int result = CommaTailType.INSTANCE.processTail(editor, tailOffset);
      if (myOriginalCall.isValid()) {
        PsiDocumentManager.getInstance(myOriginalCall.getProject()).commitDocument(editor.getDocument());
        if (myOriginalCall.isValid()) ParameterHintsPass.asyncUpdate(myOriginalCall, editor);
      }
      return result;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CommaTailTypeWithSyncHintUpdate update = (CommaTailTypeWithSyncHintUpdate)o;
      return myOriginalCall.equals(update.myOriginalCall);
    }

    @Override
    public int hashCode() {
      return Objects.hash(myOriginalCall);
    }
  }
}
