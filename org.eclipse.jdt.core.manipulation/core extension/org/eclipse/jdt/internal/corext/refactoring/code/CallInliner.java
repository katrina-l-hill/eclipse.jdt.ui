/*******************************************************************************
 * Copyright (c) 2000, 2025 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Dmitry Stalnov (dstalnov@fusionone.com) - contributed fixes for:
 *       o bug "inline method - doesn't handle implicit cast" (see
 *         https://bugs.eclipse.org/bugs/show_bug.cgi?id=24941).
 *       o bug inline method: compile error (array related)
 *         (see https://bugs.eclipse.org/bugs/show_bug.cgi?id=38471)
 *       o inline call that is used in a field initializer
 *         (see https://bugs.eclipse.org/bugs/show_bug.cgi?id=38137)
 *       o inline call a field initializer: could detect self reference
 *         (see https://bugs.eclipse.org/bugs/show_bug.cgi?id=44417)
 *       o Allow 'this' constructor to be inlined
 *         (see https://bugs.eclipse.org/bugs/show_bug.cgi?id=38093)
 *     Nikolay Metchev <nikolaymetchev@gmail.com> - Anonymous class using final parameter breaks method inlining - https://bugs.eclipse.org/269401
 *     Microsoft Corporation - copied to jdt.core.manipulation
 *     Pierre-Yves B. <pyvesdev@gmail.com> - [inline] Inlining a local variable leads to ambiguity with overloaded methods - https://bugs.eclipse.org/434747
 *     Microsoft Corporation - read formatting options from the compilation unit
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.code;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;

import org.eclipse.core.filebuffers.ITextFileBuffer;

import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.TextEditGroup;

import org.eclipse.jface.text.BadLocationException;

import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;

import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.NamingConventions;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.LabeledStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.YieldStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite.ImportRewriteContext;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import org.eclipse.jdt.internal.core.manipulation.JavaManipulationPlugin;
import org.eclipse.jdt.internal.core.manipulation.StubUtility;
import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.core.manipulation.dom.NecessaryParenthesesChecker;
import org.eclipse.jdt.internal.corext.CorextCore;
import org.eclipse.jdt.internal.corext.codemanipulation.ContextSensitiveImportRewriteContext;
import org.eclipse.jdt.internal.corext.dom.ASTNodeFactory;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.AbortSearchException;
import org.eclipse.jdt.internal.corext.dom.CodeScopeBuilder;
import org.eclipse.jdt.internal.corext.dom.HierarchicalASTVisitor;
import org.eclipse.jdt.internal.corext.dom.LocalVariableIndex;
import org.eclipse.jdt.internal.corext.dom.Selection;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatusCodes;
import org.eclipse.jdt.internal.corext.refactoring.code.flow.FlowContext;
import org.eclipse.jdt.internal.corext.refactoring.code.flow.FlowInfo;
import org.eclipse.jdt.internal.corext.refactoring.code.flow.InputFlowAnalyzer;
import org.eclipse.jdt.internal.corext.refactoring.typeconstraints.types.TypeEnvironment;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaStatusContext;
import org.eclipse.jdt.internal.corext.refactoring.util.NoCommentSourceRangeComputer;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringFileBuffers;

public class CallInliner {

	private ICompilationUnit fCUnit;
	private ASTRewrite fRewrite;
	private ImportRewrite fImportRewrite;
	private ITextFileBuffer fBuffer;
	private SourceProvider fSourceProvider;
	private TypeEnvironment fTypeEnvironment;

	private BodyDeclaration fBodyDeclaration;
	private CodeScopeBuilder.Scope fRootScope;
	private int fNumberOfLocals;

	private ASTNode fInvocation;

	private int fInsertionIndex;
	private ListRewrite fListRewrite;

	private boolean fNeedsStatement;
	private ASTNode fTargetNode;
	private FlowContext fFlowContext;
	private FlowInfo fFlowInfo;
	private CodeScopeBuilder.Scope fInvocationScope;
	private boolean fFieldInitializer;
	private List<VariableDeclarationStatement> fLocals;
	private Block fBlock;
	private CallContext fContext;

	private class InlineEvaluator extends HierarchicalASTVisitor {
		private ParameterData fFormalArgument;
		private boolean fResult;
		public InlineEvaluator(ParameterData argument) {
			fFormalArgument= argument;
		}
		public boolean getResult() {
			return fResult;
		}
		private boolean setResult(boolean result) {
			fResult= result;
			return false;
		}
		@Override
		public boolean visit(Expression node) {
			int accessMode= fFormalArgument.getSimplifiedAccessMode();
			if (accessMode == FlowInfo.WRITE)
				return setResult(false);
			if (accessMode == FlowInfo.UNUSED)
				return setResult(true);
			if (ASTNodes.isLiteral(node))
				return setResult(true);
			return setResult(fFormalArgument.getNumberOfAccesses() <= 1);
		}
		@Override
		public boolean visit(SimpleName node) {
			IBinding binding= node.resolveBinding();
			if (binding instanceof IVariableBinding) {
				int accessMode = fFormalArgument.getSimplifiedAccessMode();
				if (fFormalArgument.isFinal() && !Modifier.isFinal(binding.getModifiers())) {
					return setResult(false);
				}
				if (accessMode == FlowInfo.READ || accessMode == FlowInfo.UNUSED)
					return setResult(true);
				// from now on we only have write accesses.
				IVariableBinding vb= (IVariableBinding)binding;
				if (vb.isField())
					return setResult(false);
				return setResult(fFlowInfo.hasAccessMode(fFlowContext, vb, FlowInfo.UNUSED | FlowInfo.WRITE));
			}
			return setResult(false);
		}
		@Override
		public boolean visit(FieldAccess node) {
			return visit(node.getName());
		}
		@Override
		public boolean visit(SuperFieldAccess node) {
			return visit(node.getName());
		}
		@Override
		public boolean visit(ThisExpression node) {
			int accessMode= fFormalArgument.getSimplifiedAccessMode();
			if (accessMode == FlowInfo.READ || accessMode == FlowInfo.UNUSED)
				return setResult(true);
			return setResult(false);
		}
	}

	public CallInliner(ICompilationUnit unit, CompilationUnit targetAstRoot, SourceProvider provider) throws CoreException {
		super();
		fCUnit= unit;
		fBuffer= RefactoringFileBuffers.acquire(fCUnit);
		fSourceProvider= provider;
		fImportRewrite= StubUtility.createImportRewrite(targetAstRoot, true);
		fLocals= new ArrayList<>(3);
		fRewrite= ASTRewrite.create(targetAstRoot.getAST());
		fRewrite.setTargetSourceRangeComputer(new NoCommentSourceRangeComputer());
		fTypeEnvironment= new TypeEnvironment();
		fBlock = null;
	}

	public void dispose() {
		try {
			RefactoringFileBuffers.release(fCUnit);
		} catch (CoreException exception) {
			JavaManipulationPlugin.log(exception);
		}
	}


	public ImportRewrite getImportEdit() {
		return fImportRewrite;
	}

	public ASTNode getTargetNode() {
		return fTargetNode;
	}

	public void initialize(BodyDeclaration declaration) {
		fBodyDeclaration= declaration;
		fRootScope= CodeScopeBuilder.perform(declaration, fSourceProvider.getDeclaration().resolveBinding());
		fNumberOfLocals= 0;
		switch (declaration.getNodeType()) {
			case ASTNode.METHOD_DECLARATION:
			case ASTNode.INITIALIZER:
				fNumberOfLocals= LocalVariableIndex.perform(declaration);
				break;
		}
	}

	public RefactoringStatus initialize(ASTNode invocation, int severity) {
		RefactoringStatus result= new RefactoringStatus();
		fInvocation= invocation;
		fLocals= new ArrayList<>(3);

		checkMethodDeclaration(result, severity);
		if (result.getSeverity() >= severity)
			return result;

		checkInliningToStatic(result, severity);

		initializeRewriteState();
		initializeTargetNode();
		flowAnalysis();

		fContext= new CallContext(fInvocation, fInvocationScope, fTargetNode.getNodeType(), fImportRewrite);

		try {
			computeRealArguments();
			computeReceiver();
		} catch (BadLocationException exception) {
			JavaManipulationPlugin.log(exception);
		}
		checkInvocationContext(result, severity);
		checkAccessCompatibility(result, severity);

		return result;
	}

	// checks if incompatible references will be in-lined into static method
	private void checkInliningToStatic(RefactoringStatus result, int severity) {
		if (!fSourceProvider.isStatic()) {
			MethodDeclaration targetMethodDeclaration= ASTNodes.getFirstAncestorOrNull(fInvocation, MethodDeclaration.class);
			if (targetMethodDeclaration != null && Modifier.isStatic(targetMethodDeclaration.getModifiers())) {
				if (fSourceProvider.hasSuperMethodInvocation() || fSourceProvider.hasSuperFieldAccess()) {
					result.addEntry(new RefactoringStatusEntry(
							severity,
							RefactoringCoreMessages.CallInliner_incompatible_super_call_for_static_method,
							JavaStatusContext.create(fCUnit, fInvocation)));
				}
			}
		}
	}

	private void initializeRewriteState() {
		fFieldInitializer= false;
		ASTNode parent= fInvocation.getParent();
		do {
			if (parent instanceof FieldDeclaration) {
				fFieldInitializer= true;
				return;
			} else if (parent instanceof Block) {
				return;
			}
			parent= parent.getParent();
		} while (parent != null);
	}

	private void initializeTargetNode() {
		ASTNode parent= fInvocation.getParent();
		int nodeType= parent.getNodeType();
		if (nodeType == ASTNode.EXPRESSION_STATEMENT
				|| nodeType == ASTNode.RETURN_STATEMENT
				|| nodeType == ASTNode.YIELD_STATEMENT) {
			fTargetNode= parent;
		} else {
			fTargetNode= fInvocation;
		}
	}

	// the checks depend on invocation context and therefore can't be done in SourceAnalyzer
	private void checkMethodDeclaration(RefactoringStatus result, int severity) {
		MethodDeclaration methodDeclaration= fSourceProvider.getDeclaration();
		// it is not allowed to inline constructor invocation only if it is used for class instance creation
		// if constructor is invoked from another constructor then we can inline such invocation
		if (fInvocation.getNodeType() != ASTNode.CONSTRUCTOR_INVOCATION && methodDeclaration.isConstructor()) {
			result.addEntry(new RefactoringStatusEntry(
				severity,
				RefactoringCoreMessages.CallInliner_constructors,
				JavaStatusContext.create(fCUnit, fInvocation)));
		}
		if (fSourceProvider.hasSuperMethodInvocation() && fInvocation.getNodeType() == ASTNode.METHOD_INVOCATION) {
			Expression receiver= ((MethodInvocation)fInvocation).getExpression();
			if (receiver instanceof ThisExpression) {
				result.addEntry(new RefactoringStatusEntry(
					severity,
					RefactoringCoreMessages.CallInliner_super_into_this_expression,
					JavaStatusContext.create(fCUnit, fInvocation)));
			}
		}
	}

	private void checkAccessCompatibility(RefactoringStatus result, int severity) {
		try {
			result.merge(fSourceProvider.checkAccessCompatible(fTargetNode));
		} catch (JavaModelException e) {
			result.addEntry(new RefactoringStatusEntry(
					severity,
					RefactoringCoreMessages.CallInliner_unexpected_model_exception,
					JavaStatusContext.create(fCUnit, fInvocation)));
		}
	}

	private void checkInvocationContext(RefactoringStatus result, int severity) {
		if (fInvocation.getNodeType() == ASTNode.METHOD_INVOCATION) {
			if (((MethodInvocation)fInvocation).resolveTypeBinding() == null) {
				addEntry(result, RefactoringCoreMessages.CallInliner_receiver_type,
					RefactoringStatusCodes.INLINE_METHOD_NULL_BINDING, severity);
				return;
			}
		}
		int nodeType= fTargetNode.getNodeType();
		if (nodeType == ASTNode.EXPRESSION_STATEMENT) {
			if (fSourceProvider.isExecutionFlowInterrupted()) {
				addEntry(result, RefactoringCoreMessages.CallInliner_execution_flow,
					RefactoringStatusCodes.INLINE_METHOD_EXECUTION_FLOW, severity);
				return;
			}
		} else if (nodeType == ASTNode.METHOD_INVOCATION) {
			ASTNode parent= fTargetNode.getParent();
			if (isReturnStatement(parent)) {
				//support inlining even if the execution flow is interrupted
				return;
			}
			if (fSourceProvider.isExecutionFlowInterrupted()) {
				addEntry(result, RefactoringCoreMessages.CallInliner_execution_flow,
					RefactoringStatusCodes.INLINE_METHOD_EXECUTION_FLOW, severity);
				return;
			}
			if (isAssignment(parent) || isSingleDeclaration(parent) || isLambda(parent)) {
				// we support inlining expression in assignment and initializers as
				// long as the execution flow isn't interrupted.  we also aupport lambda bodies.
				return;
			} else {
				boolean isFieldDeclaration= ASTNodes.getParent(fInvocation, FieldDeclaration.class) != null;
				if (!fSourceProvider.isSimpleFunction()) {
					if (isMultiDeclarationFragment(parent)) {
						addEntry(result, RefactoringCoreMessages.CallInliner_multiDeclaration,
							RefactoringStatusCodes.INLINE_METHOD_INITIALIZER_IN_FRAGEMENT, severity);
					} else if (isFieldDeclaration) {
						addEntry(result,
							RefactoringCoreMessages.CallInliner_field_initializer_simple,
							RefactoringStatusCodes.INLINE_METHOD_FIELD_INITIALIZER, severity);
					} else {
						addEntry(result, RefactoringCoreMessages.CallInliner_simple_functions,
							RefactoringStatusCodes.INLINE_METHOD_ONLY_SIMPLE_FUNCTIONS, severity);
					}
					return;
				}
				if (isFieldDeclaration) {
					int argumentsCount= fContext.arguments.length;
					for (int i= 0; i < argumentsCount; i++) {
						ParameterData parameter= fSourceProvider.getParameterData(i);
						if(parameter.isWrite()) {
							addEntry(result,
								RefactoringCoreMessages.CallInliner_field_initialize_write_parameter,
								RefactoringStatusCodes.INLINE_METHOD_FIELD_INITIALIZER, severity);
							return;
						}
					}
					if(fLocals.size() > 0) {
						addEntry(result,
							RefactoringCoreMessages.CallInliner_field_initialize_new_local,
							RefactoringStatusCodes.INLINE_METHOD_FIELD_INITIALIZER, severity);
						return;
					}
					// verify that the field is not referenced by the initializer method
					VariableDeclarationFragment variable= (VariableDeclarationFragment)ASTNodes.getParent(fInvocation, ASTNode.VARIABLE_DECLARATION_FRAGMENT);
					if(fSourceProvider.isVariableReferenced(variable.resolveBinding())) {
						addEntry(result,
							RefactoringCoreMessages.CallInliner_field_initialize_self_reference,
							RefactoringStatusCodes.INLINE_METHOD_FIELD_INITIALIZER, severity);
						return;
					}
				}
			}
		}
	}

	private static boolean isAssignment(ASTNode node) {
		return node instanceof Assignment;
	}

	private static boolean isReturnStatement(ASTNode node) {
		return node instanceof ReturnStatement;
	}

	private static boolean isSingleDeclaration(ASTNode node) {
		int type= node.getNodeType();
		if (type == ASTNode.SINGLE_VARIABLE_DECLARATION)
			return true;
		if (type == ASTNode.VARIABLE_DECLARATION_FRAGMENT) {
			node= node.getParent();
			if (node.getNodeType() == ASTNode.VARIABLE_DECLARATION_STATEMENT) {
				VariableDeclarationStatement vs= (VariableDeclarationStatement)node;
				return vs.fragments().size() == 1;
			}
		}
		return false;
	}

	private static boolean isLambda(ASTNode node) {
		int type= node.getNodeType();
		if (type == ASTNode.LAMBDA_EXPRESSION)
			return true;
		return false;
	}

	private static boolean isMultiDeclarationFragment(ASTNode node) {
		int nodeType= node.getNodeType();
		if (nodeType == ASTNode.VARIABLE_DECLARATION_FRAGMENT) {
			node= node.getParent();
			if (node.getNodeType() == ASTNode.VARIABLE_DECLARATION_STATEMENT) {
				VariableDeclarationStatement vs= (VariableDeclarationStatement)node;
				return vs.fragments().size() > 1;
			}
		}
		return false;
	}

	private void addEntry(RefactoringStatus result, String message, int code, int severity) {
		result.addEntry(new RefactoringStatusEntry(
			severity, message,
			JavaStatusContext.create(fCUnit, fInvocation),
			CorextCore.getPluginId(),
			code, null));
	}

	private void flowAnalysis() {
		fInvocationScope= fRootScope.findScope(fTargetNode.getStartPosition(), fTargetNode.getLength());
		fInvocationScope.setCursor(fTargetNode.getStartPosition());
		fFlowContext= new FlowContext(0, fNumberOfLocals + 1);
		fFlowContext.setConsiderAccessMode(true);
		fFlowContext.setComputeMode(FlowContext.ARGUMENTS);
		Selection selection= Selection.createFromStartLength(fInvocation.getStartPosition(), fInvocation.getLength());
		switch (fBodyDeclaration.getNodeType()) {
			case ASTNode.INITIALIZER:
			case ASTNode.FIELD_DECLARATION:
			case ASTNode.METHOD_DECLARATION:
			case ASTNode.ENUM_CONSTANT_DECLARATION:
				fFlowInfo= new InputFlowAnalyzer(fFlowContext, selection, true).perform(fBodyDeclaration);
				break;
			default:
				Assert.isTrue(false, "Should not happen");			 //$NON-NLS-1$
		}
	}

	public RefactoringStatus perform(TextEditGroup textEditGroup) throws CoreException {
		RefactoringStatus result= new RefactoringStatus();
		String[] blocks= fSourceProvider.getCodeBlocks(fContext, fImportRewrite);
		if(!fFieldInitializer) {
			initializeInsertionPoint(fSourceProvider.getNumberOfStatements() + fLocals.size());
		}

		addNewLocals(textEditGroup);

		// if we are replacing a single method invocation which is in an implicit yield statement
		// of a SwitchExpression, we need to check if we are about to replace it with a non-implicit
		// yield statement in which case, we need to remove the yield qualifier.
		if (fContext.callMode == ASTNode.YIELD_STATEMENT && fTargetNode instanceof YieldStatement yieldStatement
				&& yieldStatement.isImplicit() && fBlock == null && blocks.length > 0 && blocks[0].startsWith("yield ")) { //$NON-NLS-1$
			blocks[0]= blocks[0].substring(6);
		}
		replaceCall(result, blocks, textEditGroup);
		return result;
	}

	public TextEdit getModifications() {
		return fRewrite.rewriteAST(fBuffer.getDocument(), fCUnit.getOptions(true));
	}


	private class InstanceofChecker extends ASTVisitor {
		@Override
		public boolean visit(InstanceofExpression node) {
			throw new AbortSearchException();
		}
	}

	private void computeRealArguments() {
		List<Expression> arguments= Invocations.getArguments(fInvocation);
		Set<Expression> canNotInline= crossCheckArguments(arguments);
		boolean needsVarargBoxing= needsVarargBoxing(arguments);
		int varargIndex= fSourceProvider.getVarargIndex();
		AST ast= fInvocation.getAST();
		Expression[] realArguments= new Expression[needsVarargBoxing ? varargIndex + 1 : arguments.size()];
		for (int i= 0; i < (needsVarargBoxing ? varargIndex : arguments.size()); i++) {
			Expression expression= arguments.get(i);
			ParameterData parameter= fSourceProvider.getParameterData(i);
			if (canInline(expression, parameter) && !canNotInline.contains(expression)) {
				realArguments[i]= expression;
			} else {
				String name= fInvocationScope.createName(parameter.getName(), true);
				realArguments[i]= ast.newSimpleName(name);
				boolean needInstanceofCheck= false;
				if (expression instanceof CastExpression) {
					ASTNode ancestor= ASTNodes.getFirstAncestorOrNull(fInvocation, ConditionalExpression.class, Statement.class);
					while (ancestor instanceof ConditionalExpression condExp) {
						InstanceofChecker checker= new InstanceofChecker();
						Expression posExp= condExp.getExpression();
						try {
							posExp.accept(checker);
						} catch (AbortSearchException e) {
							needInstanceofCheck= true;
							break;
						}
					}
				}
				Expression newExp= null;
				if (needInstanceofCheck) {
					CastExpression castExp= (CastExpression)expression;
					Type t= castExp.getType();
					InstanceofExpression instExp= ast.newInstanceofExpression();
					instExp.setRightOperand((Type) fRewrite.createCopyTarget(t));
					instExp.setLeftOperand((Expression)fRewrite.createCopyTarget(castExp.getExpression()));
					ConditionalExpression condExp= ast.newConditionalExpression();
					condExp.setExpression(instExp);
					condExp.setThenExpression((Expression)fRewrite.createCopyTarget(expression));
					condExp.setElseExpression(ast.newNullLiteral());
					newExp= condExp;
				} else {
					newExp= (Expression)fRewrite.createCopyTarget(expression);
				}
				VariableDeclarationStatement local= createLocalDeclaration(parameter.getTypeBinding(), name, newExp);
				if (parameter.isFinal()) {
					local.modifiers().add(fInvocation.getAST().newModifier(ModifierKeyword.FINAL_KEYWORD));
				}
				fLocals.add(local);
			}
		}
		if (needsVarargBoxing) {
			ParameterData parameter= fSourceProvider.getParameterData(varargIndex);
			String name= fInvocationScope.createName(parameter.getName(), true);
			realArguments[varargIndex]= ast.newSimpleName(name);
			Type type= fImportRewrite.addImport(parameter.getTypeBinding(), ast);
			VariableDeclarationFragment fragment= ast.newVariableDeclarationFragment();
			fragment.setName(ast.newSimpleName(name));
			ArrayInitializer initializer= ast.newArrayInitializer();
			for (int i= varargIndex; i < arguments.size(); i++) {
				initializer.expressions().add(fRewrite.createCopyTarget(arguments.get(i)));
			}
			fragment.setInitializer(initializer);
			VariableDeclarationStatement decl= ast.newVariableDeclarationStatement(fragment);
			decl.setType(type);
			fLocals.add(decl);
		}
		fContext.compilationUnit= fCUnit;
		fContext.arguments= realArguments;
	}

	private boolean needsVarargBoxing(List<Expression> arguments) {
		if (!fSourceProvider.isVarargs())
			return false;
		/*
		if (!fSourceProvider.hasArrayAccess())
			return false;
		*/
		int index= fSourceProvider.getVarargIndex();
		// we have varags but the call doesn't pass any arguments
		if (index >= arguments.size())
			return true;
		// parameter is array type
		// one arg
		if (index == arguments.size() - 1) {
			ITypeBinding argument= arguments.get(index).resolveTypeBinding();
			if (argument == null)
				return false;
			ITypeBinding parameter= fSourceProvider.getParameterData(index).getTypeBinding();
			return !fTypeEnvironment.create(argument).canAssignTo(fTypeEnvironment.create(parameter));
		}
		return true;
	}

	private void computeReceiver() throws BadLocationException {
		Expression receiver= Invocations.getExpression(fInvocation);
		if (receiver == null)
			return;
		final boolean isName= receiver instanceof Name;
		if (isName)
			fContext.receiverIsStatic= ((Name)receiver).resolveBinding() instanceof ITypeBinding;
		if (ASTNodes.isLiteral(receiver) || isName || receiver instanceof ThisExpression) {
			fContext.receiver= fBuffer.getDocument().get(receiver.getStartPosition(), receiver.getLength());
			return;
		}
		switch(fSourceProvider.getReceiversToBeUpdated()) {
			case 0:
				// Make sure we evaluate the current receiver. Best is to assign to
				// local.
				fLocals.add(createLocalDeclaration(
					receiver.resolveTypeBinding(),
					fInvocationScope.createName("r", true),  //$NON-NLS-1$
					(Expression)fRewrite.createCopyTarget(receiver)));
				return;
			case 1:
				fContext.receiver= fBuffer.getDocument().get(receiver.getStartPosition(), receiver.getLength());
				return;
			default:
				String local= fInvocationScope.createName("r", true); //$NON-NLS-1$
					fLocals.add(createLocalDeclaration(
					receiver.resolveTypeBinding(),
					local,
					(Expression)fRewrite.createCopyTarget(receiver)));
				fContext.receiver= local;
				return;
		}
	}

	private void addNewLocals(TextEditGroup textEditGroup) {
		fBlock = null;
		if (fLocals.isEmpty())
			return;
		if (needToCreateBlockStatement()) {
			Block block= fTargetNode.getAST().newBlock();

			for (VariableDeclarationStatement variableDeclarationStatement : fLocals) {
				ASTNode element= variableDeclarationStatement;
				block.statements().add(element);
			}
			fRewrite.replace(fTargetNode, block, textEditGroup);
			fBlock = block;
		}
		else {
			for (VariableDeclarationStatement variableDeclarationStatement : fLocals) {
				ASTNode element= variableDeclarationStatement;
				fListRewrite.insertAt(element, fInsertionIndex++, textEditGroup);
			}
		}
	}

	private boolean needToCreateBlockStatement() {
		boolean need = false;
		if (fContext != null && ASTNode.YIELD_STATEMENT == fContext.callMode) {
			if (fTargetNode !=  null && fTargetNode instanceof YieldStatement) {
				ASTNode parent = fTargetNode;
				while (parent != null) {
					if (parent instanceof SwitchExpression) {
						need = true;
						break;
					}
					if (parent instanceof Block) {
						break;
					}
					parent = parent.getParent();
				}
			}
		}
		return need;
	}

	private void replaceCall(RefactoringStatus status, String[] blocks, TextEditGroup textEditGroup) {
		// Inline empty body
		if (blocks.length == 0 && fTargetNode != null) {
			if (fNeedsStatement) {
				fRewrite.replace(fTargetNode, fTargetNode.getAST().newEmptyStatement(), textEditGroup);
			} else {
				if (fTargetNode.getLocationInParent() == LambdaExpression.BODY_PROPERTY) {
					ASTNode newNode= fRewrite.createStringPlaceholder("{}", ASTNode.BLOCK); //$NON-NLS-1$
					fRewrite.replace(fTargetNode, newNode, textEditGroup);
				} else if (fTargetNode instanceof MethodReference methodRef) {
					IMethodBinding binding= methodRef.resolveMethodBinding();
					if (binding != null) {
						StringBuilder builder= new StringBuilder("("); //$NON-NLS-1$
						String[] parmNames= binding.getParameterNames();
						String separator= ""; //$NON-NLS-1$
						for (String parmName : parmNames) {
							builder.append(separator + parmName);
							separator= ", "; //$NON-NLS-1$
						}
						builder.append(") -> {}"); //$NON-NLS-1$
						ASTNode newNode= fRewrite.createStringPlaceholder(builder.toString(), ASTNode.LAMBDA_EXPRESSION);
						fRewrite.replace(fTargetNode, newNode, textEditGroup);
					}
				} else {
					fRewrite.remove(fTargetNode, textEditGroup);
				}
			}
		} else if (fTargetNode instanceof MethodReference methodRef) {
			IMethodBinding binding= methodRef.resolveMethodBinding();
			ITypeBinding typeBinding= methodRef.resolveTypeBinding();
			if (binding == null || typeBinding == null) {
				status.addError(RefactoringCoreMessages.CallInliner_unexpected_model_exception,
						JavaStatusContext.create(fCUnit, fInvocation));
				return;
			}
			IMethodBinding[] functionClassMethods= typeBinding.getDeclaredMethods();

			StringBuilder builder= new StringBuilder("("); //$NON-NLS-1$
			String[] parmNames= binding.getParameterNames();
			String separator= ""; //$NON-NLS-1$
			for (String parmName : parmNames) {
				builder.append(separator + parmName);
				separator= ", "; //$NON-NLS-1$
			}
			builder.append(") -> {"); //$NON-NLS-1$
			String allblocks= ""; //$NON-NLS-1$
			for (int i= 0; i < blocks.length; ++i) {
				allblocks += blocks[i];
			}
			String[] lines= allblocks.split("\n"); //$NON-NLS-1$
			separator= lines.length == 1 ? "" : "\n\t"; //$NON-NLS-1$ //$NON-NLS-2$
			for (int i= 0; i < lines.length - 1; ++i) {
				builder.append(separator);
				builder.append(lines[i]);
				separator= "\n\t"; //$NON-NLS-1$
			}
			builder.append(separator);
			String[] statements= lines[lines.length - 1].split(";"); //$NON-NLS-1$
			for (int i= 0; i < statements.length - 1; ++i) {
				builder.append(statements[i]);
				builder.append("; "); //$NON-NLS-1$
			}
			if (binding.getReturnType() != null && (!binding.getReturnType().isPrimitive() || !binding.getReturnType().getName().equals("void"))) { //$NON-NLS-1$
				ITypeBinding functionMethodType= functionClassMethods[0].getReturnType();
				if (functionMethodType != null && (!functionMethodType.isPrimitive() || !functionMethodType.getName().equals("void"))) { //$NON-NLS-1$
					builder.append("return "); //$NON-NLS-1$
				} else {
					// we have a method that returns something but the functional interface is
					// void so we will copy over the contents in case they modify something external
					// and instead of a return statement, we will make an unused assignment at the end
					builder.append("@SuppressWarnings(\"unused\") "); //$NON-NLS-1$
					builder.append(binding.getReturnType().getName() + " "); //$NON-NLS-1$
					Statement body= ASTNodes.getFirstAncestorOrNull(methodRef, Statement.class);
					if (body != null) {
						List<String> excludedNames= Arrays.asList(ASTResolving.getUsedVariableNames(body));
						final List<String> sourceNames= new ArrayList<>();
						ASTVisitor visitor= new ASTVisitor() {
							@Override
							public boolean visit(SimpleName node) {
								sourceNames.add(node.getFullyQualifiedName());
								return false;
							}
						};
						fSourceProvider.getDeclaration().accept(visitor);
						sourceNames.addAll(excludedNames);
						String[] varNames= StubUtility.getVariableNameSuggestions(NamingConventions.VK_LOCAL, fImportRewrite.getCompilationUnit().getJavaProject(), functionMethodType, null, sourceNames);
						builder.append(varNames[0] + " = "); //$NON-NLS-1$
					}
				}
			}
			builder.append(statements[statements.length - 1]);
			builder.append(";"); //$NON-NLS-1$
			separator= lines.length == 1 ? "" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
			builder.append(separator + "}"); //$NON-NLS-1$
			ASTNode newNode= fRewrite.createStringPlaceholder(builder.toString(), ASTNode.LAMBDA_EXPRESSION);
			fRewrite.replace(fTargetNode, newNode, textEditGroup);
		} else if (fTargetNode != null && fTargetNode.getLocationInParent() == LambdaExpression.BODY_PROPERTY) {
			String allblocks= ""; //$NON-NLS-1$
			for (int i= 0; i < blocks.length; ++i) {
				allblocks += blocks[i];
			}
			StringBuilder builder= new StringBuilder();
			builder.append("{"); //$NON-NLS-1$
			String[] lines= allblocks.split("\n"); //$NON-NLS-1$
			String separator= lines.length == 1 ? "" : "\n\t"; //$NON-NLS-1$ //$NON-NLS-2$
			for (int i= 0; i < lines.length; ++i) {
				builder.append(separator);
				builder.append(lines[i]);
				separator= "\n\t"; //$NON-NLS-1$
			}
			separator= lines.length == 1 ? "" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
			builder.append(separator + "}"); //$NON-NLS-1$
			ASTNode newNode= fRewrite.createStringPlaceholder(builder.toString(), ASTNode.BLOCK);
			fRewrite.replace(fTargetNode, newNode, textEditGroup);
		} else {
			ASTNode node= null;
			boolean needsMethodInvocation= true;
			for (int i= 0; i < blocks.length - 1; i++) {
				node= fRewrite.createStringPlaceholder(blocks[i], ASTNode.RETURN_STATEMENT);
				fListRewrite.insertAt(node, fInsertionIndex++, textEditGroup);
			}
			String block= blocks[blocks.length - 1];
			// We can inline a call where the declaration is a function and the call itself
			// is a statement. In this case we have to create a temporary variable if the
			// returned expression must be evaluated.
			if (fContext.callMode == ASTNode.EXPRESSION_STATEMENT && fSourceProvider.hasReturnValue()) {
				if (fSourceProvider.mustEvaluateReturnedExpression()) {
					if (fSourceProvider.returnValueNeedsLocalVariable()) {
						IMethodBinding invocation= Invocations.resolveBinding(fInvocation);
						node= createLocalDeclaration(
							invocation.getReturnType(),
							fInvocationScope.createName(fSourceProvider.getMethodName(), true),
							(Expression)fRewrite.createStringPlaceholder(block, ASTNode.METHOD_INVOCATION));
					} else {
						node= fRewrite.getAST().newExpressionStatement(
								(Expression)fRewrite.createStringPlaceholder(block, ASTNode.METHOD_INVOCATION));
					}
					if (fSourceProvider.isSynchronized()) {
						node= createSyncBlock(node, status);
					}
				} else {
					node= null;
				}
			} else if (fTargetNode instanceof Expression) {
				if (fSourceProvider.isSynchronized()) {
					status.addWarning(RefactoringCoreMessages.CallInliner_cannot_synchronize_error,
							JavaStatusContext.create(fCUnit, fInvocation));
				}
				String[] segments= block.split(";"); //$NON-NLS-1$
				if (segments.length == 2 && !segments[1].trim().isEmpty()) {
					Statement targetStatement= ASTNodes.getFirstAncestorOrNull(fTargetNode, Statement.class);
					if (targetStatement != null) {
						ASTNode root= fTargetNode.getRoot();
						if (root instanceof CompilationUnit cu && cu.getJavaElement() instanceof ICompilationUnit icu) {
							int start= targetStatement.getStartPosition();
							int length= targetStatement.getLength();
							String targetStatementString= null;
							try {
								IBuffer buffer= icu.getBuffer();
								targetStatementString= buffer.getText(start, length);
							} catch (JavaModelException e) {
								// should never occur
							}
							if (targetStatementString != null) {
								int expOffset= fTargetNode.getStartPosition() - start;
								String newTargetStatement= targetStatementString.substring(0, expOffset);
								newTargetStatement += segments[0];
								newTargetStatement += targetStatementString.substring(expOffset + fTargetNode.getLength());
								newTargetStatement += " " + segments[1].trim(); //$NON-NLS-1$
								fTargetNode= targetStatement;
								node= fRewrite.createStringPlaceholder(newTargetStatement, targetStatement.getNodeType());
								needsMethodInvocation= false;
							}
						}
					}
				}
				if (needsMethodInvocation) {
					node= fRewrite.createStringPlaceholder(block, ASTNode.METHOD_INVOCATION);

					// fixes bug #24941
					if (needsExplicitCast(status)) {
						AST ast= node.getAST();
						CastExpression castExpression= ast.newCastExpression();
						Type returnType= fImportRewrite.addImport(fSourceProvider.getReturnType(), ast);
						castExpression.setType(returnType);

						if (NecessaryParenthesesChecker.needsParentheses(fSourceProvider.getReturnExpressions().get(0), castExpression, CastExpression.EXPRESSION_PROPERTY)) {
							ParenthesizedExpression parenthesized= ast.newParenthesizedExpression();
							parenthesized.setExpression((Expression)node);
							node= parenthesized;
						}

						castExpression.setExpression((Expression)node);
						node= castExpression;

						if (NecessaryParenthesesChecker.needsParentheses(castExpression, fTargetNode.getParent(), fTargetNode.getLocationInParent())) {
							ParenthesizedExpression parenthesized= ast.newParenthesizedExpression();
							parenthesized.setExpression((Expression)node);
							node= parenthesized;
						}
					} else if (fSourceProvider.needsReturnedExpressionParenthesis(fTargetNode.getParent(), fTargetNode.getLocationInParent())) {
						ParenthesizedExpression pExp= fTargetNode.getAST().newParenthesizedExpression();
						pExp.setExpression((Expression)node);
						node= pExp;
					}
				}
			} else if (fContext.callMode == ASTNode.YIELD_STATEMENT) {
				if (fBlock != null) {
					node= fRewrite.createStringPlaceholder(block, ASTNode.BLOCK);
				} else {
					node= fRewrite.createStringPlaceholder(block, ASTNode.YIELD_STATEMENT);
				}
				if (fSourceProvider.isSynchronized()) {
					node= createSyncBlock(node, status);
				}
			} else {
				node= fRewrite.createStringPlaceholder(block, ASTNode.RETURN_STATEMENT);
				if (fSourceProvider.isSynchronized()) {
					node= createSyncBlock(node, status);
				}
			}


			// Now replace the target node with the source node
			if (node != null) {
				if (fTargetNode == null) {
					fListRewrite.insertAt(node, fInsertionIndex++, textEditGroup);
				} else if (fBlock != null) {
					fBlock.statements().add(node);
				} else {
					fRewrite.replace(fTargetNode, node, textEditGroup);
				}
			} else {
				if (fTargetNode != null) {
					fRewrite.remove(fTargetNode, textEditGroup);
				}
			}
		}
	}

	private ASTNode createSyncBlock(ASTNode node, RefactoringStatus status) {
		AST ast= fRewrite.getAST();
		SynchronizedStatement sync=
				ast.newSynchronizedStatement();
		Expression exp= Invocations.getExpression(fInvocation);
		if (exp != null) {
			sync.setExpression((Expression)fRewrite.createCopyTarget(exp));
		} else if (fSourceProvider.isStatic()) {
			TypeLiteral literal= ast.newTypeLiteral();
			ITypeBinding binding= ASTNodes.getEnclosingType(fSourceProvider.getDeclaration());
			if (binding == null) {
				status.addError(RefactoringCoreMessages.CallInliner_cast_analysis_error,
						JavaStatusContext.create(fCUnit, fInvocation));
				return null;
			}
			Type type= fImportRewrite.addImport(binding, ast);
			literal.setType(type);
			sync.setExpression(literal);
		} else {
			sync.setExpression(ast.newThisExpression());
		}
		Block newBlock= ast.newBlock();
		sync.setBody(newBlock);
		newBlock.statements().add(node);
		node= sync;
		return node;
	}

	/**
	 * @param status the status
	 * @return <code>true</code> if explicit cast is needed otherwise <code>false</code>
	 */
	private boolean needsExplicitCast(RefactoringStatus status) {
		// if the return type of the method is the same as the type of the
		// returned expression then we don't need an explicit cast.
		if (fSourceProvider.returnTypeMatchesReturnExpressions())
				return false;

		List<Expression> returnExprs= fSourceProvider.getReturnExpressions();
		// it is inferred that only methods consisting of a single
		// return statement can be inlined as parameters in other
		// method invocations
		if (returnExprs.size() != 1)
			return false;

		if (fTargetNode.getLocationInParent() == MethodInvocation.ARGUMENTS_PROPERTY) {
			MethodInvocation methodInvocation= (MethodInvocation)fTargetNode.getParent();
			if(methodInvocation.getExpression() == fTargetNode)
				return false;
			IMethodBinding method= methodInvocation.resolveMethodBinding();
			if (method == null) {
				status.addError(RefactoringCoreMessages.CallInliner_cast_analysis_error,
					JavaStatusContext.create(fCUnit, methodInvocation));
				return false;
			}

			ITypeBinding parameterType= returnExprs.get(0).resolveTypeBinding();
			return ASTNodes.isTargetAmbiguous((Expression) fTargetNode, parameterType);
		} else {
			ITypeBinding explicitCast= ASTNodes.getExplicitCast(returnExprs.get(0), (Expression)fTargetNode);
			return explicitCast != null;
		}
	}

	private VariableDeclarationStatement createLocalDeclaration(ITypeBinding type, String name, Expression initializer) {
		ImportRewriteContext context= new ContextSensitiveImportRewriteContext(fTargetNode, fImportRewrite);
		String typeName= fImportRewrite.addImport(type, context);
		VariableDeclarationStatement decl= (VariableDeclarationStatement)ASTNodeFactory.newStatement(
			fInvocation.getAST(), typeName + " " + name + ";"); //$NON-NLS-1$ //$NON-NLS-2$
		((VariableDeclarationFragment)decl.fragments().get(0)).setInitializer(initializer);
		return decl;
	}

    /**
     * Checks whether arguments are passed to the method which do some assignments
     * inside the expression. If so these arguments can't be inlined into the
     * calling method since the assignments might be reorder. An example is:
     * <code>
     *   add((field=args).length,field.hashCode());
     * </code>
     * Field might not be initialized when the arguments are reorder in the called
     * method.
     * @param arguments the arguments
     * @return all arguments that cannot be inlined
     */
	private Set<Expression> crossCheckArguments(List<Expression> arguments) {
		final Set<IBinding> assigned= new HashSet<>();
		final Set<Expression> result= new HashSet<>();
		for (Expression expression : arguments) {
			expression.accept(new ASTVisitor() {
				@Override
				public boolean visit(Assignment node) {
					Expression lhs= node.getLeftHandSide();
					if (lhs instanceof Name) {
						IBinding binding= ((Name)lhs).resolveBinding();
						if (binding instanceof IVariableBinding) {
							assigned.add(binding);
							result.add(expression);
						}
					}
					return true;
				}
			});
		}
		for (Expression expression : arguments) {
			if (!result.contains(expression)) {
				expression.accept(new HierarchicalASTVisitor() {
					@Override
					public boolean visit(Name node) {
						IBinding binding= node.resolveBinding();
						if (binding != null && assigned.contains(binding))
							result.add(expression);
						return false;
					}
				});
			}
		}
		// if there is only 1 argument that has assignment and no others use it, there is no issue
		if (result.size() == 1) {
			return new HashSet<>();
		}
		return result;
	}

	private boolean canInline(Expression actualParameter, ParameterData formalParameter) {
		InlineEvaluator evaluator= new InlineEvaluator(formalParameter);
		actualParameter.accept(evaluator);
		return evaluator.getResult();
	}

	private void initializeInsertionPoint(int nos) {
		fInsertionIndex= -1;
		fNeedsStatement= false;
		// if we have a constructor invocation the invocation itself is already a statement
		ASTNode parentStatement= fInvocation instanceof Statement
			? fInvocation
			: ASTNodes.getParent(fInvocation, Statement.class);
		if (parentStatement == null)
			return;

		ASTNode container= parentStatement.getParent();
		int type= container.getNodeType();
		switch (type) {
			case ASTNode.BLOCK: {
				Block block= (Block)container;
				fListRewrite= fRewrite.getListRewrite(block, Block.STATEMENTS_PROPERTY);
				fInsertionIndex= fListRewrite.getRewrittenList().indexOf(parentStatement);
				break;
			}
			case ASTNode.SWITCH_STATEMENT: {
				SwitchStatement switchStatement= (SwitchStatement)container;
				fListRewrite= fRewrite.getListRewrite(switchStatement, SwitchStatement.STATEMENTS_PROPERTY);
				fInsertionIndex= fListRewrite.getRewrittenList().indexOf(parentStatement);
				break;
			}
			case ASTNode.SWITCH_EXPRESSION: {
				SwitchExpression switchExpression= (SwitchExpression)container;
				fListRewrite= fRewrite.getListRewrite(switchExpression, SwitchExpression.STATEMENTS_PROPERTY);
				fInsertionIndex= fListRewrite.getRewrittenList().indexOf(parentStatement);
				break;
			}
			default:
				if (isControlStatement(container) || type == ASTNode.LABELED_STATEMENT) {
					fNeedsStatement= true;
					if (nos > 1 || needsBlockAroundDanglingIf()) {
						Block block= fInvocation.getAST().newBlock();
						fInsertionIndex= 0;
						Statement currentStatement= null;
						switch(type) {
							case ASTNode.LABELED_STATEMENT:
								currentStatement= ((LabeledStatement)container).getBody();
								break;
							case ASTNode.FOR_STATEMENT:
								currentStatement= ((ForStatement)container).getBody();
								break;
							case ASTNode.ENHANCED_FOR_STATEMENT:
								currentStatement= ((EnhancedForStatement)container).getBody();
								break;
							case ASTNode.WHILE_STATEMENT:
								currentStatement= ((WhileStatement)container).getBody();
								break;
							case ASTNode.DO_STATEMENT:
								currentStatement= ((DoStatement)container).getBody();
								break;
							case ASTNode.IF_STATEMENT:
								IfStatement node= (IfStatement)container;
								Statement thenPart= node.getThenStatement();
								if (fTargetNode == thenPart || ASTNodes.isParent(fTargetNode, thenPart)) {
									currentStatement= thenPart;
								} else {
									currentStatement= node.getElseStatement();
								}
								break;
						}
						Assert.isNotNull(currentStatement);
						fRewrite.replace(currentStatement, block, null);
						fListRewrite= fRewrite.getListRewrite(block, Block.STATEMENTS_PROPERTY);
						// The method to be inlined is not the body of the control statement.
						if (currentStatement != fTargetNode) {
							fListRewrite.insertLast(fRewrite.createCopyTarget(currentStatement), null);
						} else {
							// We can't replace a copy with something else. So we
							// have to insert all statements to be inlined.
							fTargetNode= null;
						}
					}
				}
				break;
		}
		// We only insert one new statement or we delete the existing call.
		// So there is no need to have an insertion index.
	}

	private boolean needsBlockAroundDanglingIf() {
		/* see https://bugs.eclipse.org/bugs/show_bug.cgi?id=169331
		 *
		 * Situation:
		 * boolean a, b;
		 * void toInline() {
		 *     if (a)
		 *         hashCode();
		 * }
		 * void m() {
		 *     if (b)
		 *         toInline();
		 *     else
		 *         toString();
		 * }
		 * => needs block around inlined "if (a)..." to avoid attaching else to wrong if.
		 */
		return fTargetNode.getLocationInParent() == IfStatement.THEN_STATEMENT_PROPERTY
				&& fTargetNode.getParent().getStructuralProperty(IfStatement.ELSE_STATEMENT_PROPERTY) != null
				&& fSourceProvider.isDangligIf();
	}

	private boolean isControlStatement(ASTNode node) {
		int type= node.getNodeType();
		return type == ASTNode.IF_STATEMENT || type == ASTNode.FOR_STATEMENT || type == ASTNode.ENHANCED_FOR_STATEMENT ||
		        type == ASTNode.WHILE_STATEMENT || type == ASTNode.DO_STATEMENT;
	}
}
