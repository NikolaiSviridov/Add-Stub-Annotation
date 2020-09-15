package com.github.nikolaisviridov.testtask.intentions

import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.template.TemplateBuilderFactory
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil
import com.jetbrains.python.codeInsight.intentions.PyBaseIntentionAction
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil.AnnotationInfo
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil.Pep484IncompatibleTypeException
import com.jetbrains.python.documentation.PythonDocumentationProvider
import com.jetbrains.python.documentation.doctest.PyDocstringFile
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyAugAssignmentStatementNavigator
import com.jetbrains.python.psi.impl.PyBuiltinCache
import com.jetbrains.python.psi.impl.PyPsiUtils
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.resolve.RatedResolveResult
import com.jetbrains.python.psi.types.PyClassTypeImpl
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.TypeEvalContext
import one.util.streamex.StreamEx
import java.util.*
import java.util.function.Predicate

class AddAnnotation : PyBaseIntentionAction() {

    override fun getText(): String = "TestTask Add annotation"

    override fun getFamilyName(): String = "Test Task FamilyName"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is PyFile || file is PyDocstringFile || editor == null) {
            return false
        }

        val resolvedVars = findSuitableTargets(project, file)
        val resolvedFunctions = traverseTreeFunction(file)

        return resolvedVars.isNotEmpty() || resolvedFunctions.isNotEmpty()
    }

    override fun doInvoke(project: Project, editor: Editor?, file: PsiFile?) {

        if (editor == null || file == null) return

        val targets = findSuitableTargets(project, file)

        for (annotationTarget in targets) {
            try {
                if (preferSyntacticAnnotation(annotationTarget)) {
                    insertVariableAnnotation(annotationTarget)
                } else {
                    insertVariableTypeComment(annotationTarget)
                }
            } catch (e: Pep484IncompatibleTypeException) {
//            PythonUiService.getInstance().showErrorHint(editor, e.message)
            }
        }

        val functions = traverseTreeFunction(file)
        for (function in functions) {
            annotateTypes(editor, function)
        }

    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    private fun traverseTree(element: PsiElement, result: MutableList<PsiElement> = arrayListOf()): List<PsiElement> {
        if (element is PyTargetExpression || element is PyReferenceExpression) {
            result.add(element)
        }
        for (child in element.children) {
            traverseTree(child, result)
        }
        return result
    }

    private fun traverseTreeFunction(element: PsiElement, result: MutableList<PyFunction> = arrayListOf()): List<PyFunction> {
        val index = ProjectFileIndex.getInstance(element.project)

        if (element is PyFunction) {
            if (!index.isInLibraryClasses(element.containingFile.virtualFile)) {
                result.add(element)
            }
        }
        for (child in element.children) {
            traverseTreeFunction(child, result)
        }
        return result
    }

    private fun findSuitableTargets(project: Project, file: PsiFile): List<PyTargetExpression> {
        val index = ProjectFileIndex.getInstance(project)
        val typeEvalContext = TypeEvalContext.codeAnalysis(project, file)

        return traverseTree(file)
                .filterIsInstance<PyTargetExpression>()
                .filter { !index.isInLibraryClasses(it.containingFile.virtualFile) }
                .filter { canBeAnnotated(it) }
                .filter { !isAnnotated(it, typeEvalContext) }
    }

    private fun resolveReferenceAugAssignmentsAware(element: PyReferenceOwner,
                                                    resolveContext: PyResolveContext): StreamEx<PsiElement> {
        return StreamEx.of(PyUtil.multiResolveTopPriority(element, resolveContext))
                .filter { resolved: PsiElement -> resolved is PyTargetExpression || resolved !== element }
                .flatMap { resolved: PsiElement -> expandResolveAugAssignments(resolved, resolveContext) }
                .distinct()
    }

    private fun expandResolveAugAssignments(element: PsiElement, context: PyResolveContext): StreamEx<PsiElement> {
        return if (element is PyReferenceExpression && PyAugAssignmentStatementNavigator.getStatementByTarget(element) != null) {
            StreamEx.of(resolveReferenceAugAssignmentsAware(element as PyReferenceOwner, context))
        } else StreamEx.of(element)
    }

    private fun canBeAnnotated(target: PyTargetExpression): Boolean {
        val directParent = target.parent
        return if (directParent is PyImportElement ||
                directParent is PyComprehensionForComponent ||
                directParent is PyGlobalStatement ||
                directParent is PyNonlocalStatement) {
            false
        } else PsiTreeUtil.getParentOfType(target, PyWithItem::class.java, PyAssignmentStatement::class.java, PyForPart::class.java) != null
    }

    // TODO unify this logic with PyTypingTypeProvider somehow
    private fun isAnnotated(target: PyTargetExpression, context: TypeEvalContext): Boolean {
        val scopeOwner = ScopeUtil.getScopeOwner(target)
        val name = target.name
        if (scopeOwner == null || name == null) {
            return false
        }
        if (!target.isQualified) {
            if (hasInlineAnnotation(target)) {
                return true
            }

            var candidates: StreamEx<PyTargetExpression>? = null
            if (context.maySwitchToAST(target)) {
                val scope = ControlFlowCache.getScope(scopeOwner)
                candidates = StreamEx.of(scope.getNamedElements(name, false)).select(PyTargetExpression::class.java)
            } else if (scopeOwner is PyFile) {
                candidates = StreamEx.of(scopeOwner.topLevelAttributes).filter { t: PyTargetExpression -> name == t.name }
            } else if (scopeOwner is PyClass) {
                candidates = StreamEx.of(scopeOwner.classAttributes).filter { t: PyTargetExpression -> name == t.name }
            }

            if (candidates != null) {
                return candidates.anyMatch(Predicate { obj: PyTargetExpression -> hasInlineAnnotation(obj) })
            }
        } else if (isInstanceAttribute(target, context)) {
            // Set isDefinition=true to start searching right from the class level.
            val classLevelDefinitions = findClassLevelDefinitions(target, context)
            return ContainerUtil.exists(classLevelDefinitions, Condition { obj: PyTargetExpression -> hasInlineAnnotation(obj) })
        }
        return false
    }

    private fun findClassLevelDefinitions(target: PyTargetExpression, context: TypeEvalContext): List<PyTargetExpression> {
        assert(target.containingClass != null)
        assert(target.name != null)
        val classType = PyClassTypeImpl(target.containingClass!!, true)
        val resolveContext = PyResolveContext.defaultContext().withTypeEvalContext(context)
        val classAttrs = classType.resolveMember(target.name!!, target, AccessDirection.READ, resolveContext, true)
                ?: return emptyList()
        return StreamEx.of(classAttrs)
                .map { obj: RatedResolveResult -> obj.element }
                .select(PyTargetExpression::class.java)
                .filter { x: PyTargetExpression? -> ScopeUtil.getScopeOwner(x) is PyClass }
                .toList()
    }

    private fun isInstanceAttribute(target: PyTargetExpression, context: TypeEvalContext): Boolean {
        val scopeOwner = ScopeUtil.getScopeOwner(target)
        return if (target.isQualified && target.containingClass != null && scopeOwner is PyFunction) {
            if (context.maySwitchToAST(target)) {
                val resolveContext = PyResolveContext.defaultContext().withTypeEvalContext(context)
                StreamEx.of(PyUtil.multiResolveTopPriority(target.qualifier!!, resolveContext))
                        .select(PyParameter::class.java)
                        .filter { obj: PyParameter -> obj.isSelf }
                        .anyMatch { p: PyParameter? -> PsiTreeUtil.getParentOfType(p, PyFunction::class.java) === scopeOwner }
            } else {
                PyUtil.isInstanceAttribute(target)
            }
        } else false
    }

    private fun hasInlineAnnotation(target: PyTargetExpression): Boolean {
        return target.annotationValue != null || target.typeCommentAnnotation != null
    }

    private fun preferSyntacticAnnotation(annotationTarget: PyTargetExpression): Boolean {
        return LanguageLevel.forElement(annotationTarget).isAtLeast(LanguageLevel.PYTHON36)
    }

    private fun insertVariableAnnotation(target: PyTargetExpression) {
        val context = TypeEvalContext.userInitiated(target.project, target.containingFile)
        val inferredType = getInferredTypeOrObject(target)
        PyTypeHintGenerationUtil.checkPep484Compatibility(inferredType, context)
        val annotationText = PythonDocumentationProvider.getTypeHint(inferredType, context)
//        val info = AnnotationInfo(annotationText, inferredType)
        val info = AnnotationInfo("Any", inferredType)
        if (isInstanceAttribute(target, context)) {
            val classLevelAttrs = findClassLevelDefinitions(target, context)
            if (classLevelAttrs.isEmpty()) {
                PyTypeHintGenerationUtil.insertStandaloneAttributeAnnotation(target, context, info, false)
            } else {
                PyTypeHintGenerationUtil.insertVariableAnnotation(classLevelAttrs[0], context, info, false)
            }
        } else {
            PyTypeHintGenerationUtil.insertVariableAnnotation(target, context, info, false)
        }
    }

    private fun insertVariableTypeComment(target: PyTargetExpression) {
        val context = TypeEvalContext.userInitiated(target.project, target.containingFile)
        val info = generateNestedTypeHint(target, context)
        if (isInstanceAttribute(target, context)) {
            val classLevelAttrs = findClassLevelDefinitions(target, context)
            if (classLevelAttrs.isEmpty()) {
                PyTypeHintGenerationUtil.insertStandaloneAttributeTypeComment(target, context, info, false)
            } else {
                // Use existing class level definition (say, assignment of the default value) for annotation
                PyTypeHintGenerationUtil.insertVariableTypeComment(classLevelAttrs[0], context, info, false)
            }
        } else {
            PyTypeHintGenerationUtil.insertVariableTypeComment(target, context, info, false)
        }
    }

    private fun generateNestedTypeHint(target: PyTargetExpression, context: TypeEvalContext): AnnotationInfo {
        val validTargetParent = PsiTreeUtil.getParentOfType(target, PyForPart::class.java, PyWithItem::class.java, PyAssignmentStatement::class.java)!!
        val topmostTarget = PsiTreeUtil.findPrevParent(validTargetParent, target)
        val builder = StringBuilder()
        val types: MutableList<PyType?> = ArrayList()
        val typeRanges = ArrayList<TextRange>()
        generateNestedTypeHint(topmostTarget, context, builder, types, typeRanges)
        return AnnotationInfo(builder.toString(), types, typeRanges)
    }

    private fun generateNestedTypeHint(target: PsiElement,
                                       context: TypeEvalContext,
                                       builder: StringBuilder,
                                       types: MutableList<PyType?>,
                                       typeRanges: MutableList<TextRange>) {
        if (target is PyParenthesizedExpression) {
            val contained = target.containedExpression
            contained?.let { generateNestedTypeHint(it, context, builder, types, typeRanges) }
        } else if (target is PyTupleExpression) {
            builder.append("(")
            val elements = target.elements
            for (i in elements.indices) {
                if (i > 0) {
                    builder.append(", ")
                }
                generateNestedTypeHint(elements[i], context, builder, types, typeRanges)
            }
            builder.append(")")
        } else if (target is PyTypedElement) {
            val singleTargetType = getInferredTypeOrObject(target)
            PyTypeHintGenerationUtil.checkPep484Compatibility(singleTargetType, context)
            val singleTargetAnnotation = PythonDocumentationProvider.getTypeHint(singleTargetType, context)
            types.add(singleTargetType)
            typeRanges.add(TextRange.from(builder.length, singleTargetAnnotation.length))
            builder.append(singleTargetAnnotation)
        }
    }

    private fun getInferredTypeOrObject(target: PyTypedElement): PyType? {
        return PyBuiltinCache.getInstance(target).getObjectType("Any")
    }



    private fun annotateTypes(editor: Editor?, function: PyFunction) {
        if (editor == null) {
            return
        }

        if (!FileModificationService.getInstance().preparePsiElementForWrite(function)) return
        WriteAction.run<RuntimeException> {
            if (isPy3k(function.containingFile)) {
                generatePy3kTypeAnnotations(function.project, editor, function)
            } else {
                generateTypeCommentAnnotations(function.project, function)
            }
        }
    }

    private fun isPy3k(file: PsiFile): Boolean {
        return !LanguageLevel.forElement(file).isPython2
    }

    private fun getParamType(): String = "Any"

    private fun getFuncType(): String = "Any"

    private fun annotateParameter(project: Project?,
                                  editor: Editor,
                                  parameter: PyNamedParameter,
                                  createTemplate: Boolean): PyNamedParameter? {
        var parameter = parameter
        val defaultParamValue = parameter.defaultValue
        val paramName = StringUtil.notNullize(parameter.name)
        val elementGenerator = PyElementGenerator.getInstance(project)
        val defaultParamText = defaultParamValue?.text
        val paramType = getParamType()
        val namedParameter = elementGenerator.createParameter(paramName, defaultParamText, paramType,
                LanguageLevel.forElement(parameter))!!
        parameter = parameter.replace(namedParameter) as PyNamedParameter
        parameter = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(parameter)
        editor.caretModel.moveToOffset(parameter.textOffset)
        val annotation = parameter.annotation
        if (annotation != null && createTemplate) {
            val annotationValue = annotation.value
            val builder = TemplateBuilderFactory.getInstance().createTemplateBuilder(parameter)
            assert(annotationValue != null) { "Generated parameter must have annotation" }
            val replacementStart = annotation.startOffsetInParent + annotationValue!!.startOffsetInParent
            builder.replaceRange(TextRange.from(replacementStart, annotationValue.textLength), paramType)
            builder.run(editor, true)
        }
        return parameter
    }

    private fun annotateReturnType(function: PyFunction): PyExpression? {
        val returnType = getFuncType()
        val annotationText = "-> $returnType"
        val annotatedFunction = PyUtil.updateDocumentUnblockedAndCommitted<PyFunction>(function) { document: Document ->
            val oldAnnotation = function.annotation
            if (oldAnnotation != null) {
                val oldRange = oldAnnotation.textRange
                document.replaceString(oldRange.startOffset, oldRange.endOffset, annotationText)
            } else {
                val prevElem = PyPsiUtils.getPrevNonCommentSibling(function.statementList, true)!!
                val range = prevElem.textRange
                if (prevElem.node.elementType === PyTokenTypes.COLON) {
                    document.insertString(range.startOffset, " $annotationText")
                } else {
                    document.insertString(range.endOffset, " $annotationText:")
                }
            }
            CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(function)
        }
                ?: return null
        val annotation = annotatedFunction.annotation!!
        val annotationValue = annotation.value ?: error("Generated function must have annotation")

        return annotationValue
    }

    private fun generatePy3kTypeAnnotations(project: Project, editor: Editor, function: PyFunction) {
        annotateReturnType(function)

        val params = function.parameterList.parameters
        for (i in params.indices.reversed()) {
            if (params[i] is PyNamedParameter && !params[i].isSelf) {
                annotateParameter(project, editor, (params[i] as PyNamedParameter), false)
            }
        }
    }

    private fun generateTypeCommentAnnotations(project: Project, function: PyFunction) {
        val replacementTextBuilder = StringBuilder("# type: (")
        val params = function.parameterList.parameters
        val templates: MutableList<Pair<Int, String>> = ArrayList()
        for (i in params.indices) {
            if (!params[i].isSelf) {
                val type = getParamType()
                templates.add(Pair.create(replacementTextBuilder.length, type))
                replacementTextBuilder.append(type)
                if (i < params.size - 1) {
                    replacementTextBuilder.append(", ")
                }
            }
        }
        replacementTextBuilder.append(") -> ")
        val returnType = getFuncType()
        templates.add(Pair.create(replacementTextBuilder.length, returnType))
        replacementTextBuilder.append(returnType)
        val statements = function.statementList
        val indentation = PyIndentUtil.getElementIndent(statements)
        replacementTextBuilder.insert(0, indentation)
        replacementTextBuilder.insert(0, "\n")
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(function.containingFile)
        if (document != null) {
            val beforeStatements = statements.prevSibling
            var offset = beforeStatements.textRange.startOffset
            if (":" == beforeStatements.text) {
                offset += 1
            }
            try {
                document.insertString(offset, replacementTextBuilder.toString())
            } finally {
                manager.commitDocument(document)
            }
            var element: PsiElement? = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(function)
            while (element != null && !element.text.contains(replacementTextBuilder.toString())) {
                element = element.parent
            }
            if (element != null) {
                val builder = TemplateBuilderFactory.getInstance().createTemplateBuilder(element)
                for (template in templates) {
                    builder.replaceRange(TextRange.from(
                            offset - element.textRange.startOffset + replacementTextBuilder.toString().indexOf('#') + template.first,
                            template.second.length), template.second)
                }
            }
        }
    }


}
