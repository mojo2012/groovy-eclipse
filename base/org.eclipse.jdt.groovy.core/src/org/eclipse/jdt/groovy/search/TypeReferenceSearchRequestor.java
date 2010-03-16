/*
 * Copyright 2003-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jdt.groovy.search;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.groovy.core.util.ReflectionUtils;
import org.eclipse.jdt.groovy.search.TypeLookupResult.TypeConfidence;
import org.eclipse.jdt.internal.core.search.matching.JavaSearchPattern;
import org.eclipse.jdt.internal.core.search.matching.TypeReferencePattern;
import org.eclipse.jdt.internal.core.util.Util;

/**
 * @author Andrew Eisenberg
 * @created Aug 29, 2009
 * 
 */
public class TypeReferenceSearchRequestor implements ITypeRequestor {
	private final SearchRequestor requestor;
	private final SearchParticipant participant;

	private final String qualifier;
	private final String simpleName;
	private final boolean isCaseSensitive;
	private final boolean isCamelCase;

	@SuppressWarnings("nls")
	public TypeReferenceSearchRequestor(TypeReferencePattern pattern, SearchRequestor requestor, SearchParticipant participant) {
		this.requestor = requestor;
		this.participant = participant;
		char[] arr = (char[]) ReflectionUtils.getPrivateField(TypeReferencePattern.class, "simpleName", pattern);
		this.simpleName = arr == null ? "" : new String(arr);
		arr = (char[]) ReflectionUtils.getPrivateField(TypeReferencePattern.class, "qualification", pattern);
		this.qualifier = (arr == null || arr.length == 0) ? "" : (new String(arr) + ".");
		this.isCaseSensitive = ((Boolean) ReflectionUtils.getPrivateField(JavaSearchPattern.class, "isCaseSensitive", pattern))
				.booleanValue();
		this.isCamelCase = ((Boolean) ReflectionUtils.getPrivateField(JavaSearchPattern.class, "isCamelCase", pattern))
				.booleanValue();
	}

	public VisitStatus acceptASTNode(ASTNode node, TypeLookupResult result, IJavaElement enclosingElement) {
		// don't do constructor calls. They are found through the class node inside of it
		if (node instanceof ClassExpression || node instanceof ClassNode || node instanceof ImportNode
				|| node instanceof AnnotationNode || node instanceof ConstructorNode) {

			ASTNode noArray;
			if (node instanceof ClassNode) {
				noArray = removeArray((ClassNode) node);
			} else if (node instanceof ConstructorNode) {
				noArray = ((ConstructorNode) node).getDeclaringClass();
			} else {
				noArray = node;
			}

			ClassNode type;
			if (node instanceof ConstructorNode) {
				type = ((ConstructorNode) node).getDeclaringClass();
			} else {
				type = result.type;
			}
			if (node instanceof ClassExpression && type == VariableScope.CLASS_CLASS_NODE) {
				// special case...there is a Foo.class expression.
				// the difference between Foo.class and Foo does not appear in the AST.
				// The type of the expression is considered to be Class, but we still need to
				// look for a reference for Foo
				type = ((ClassExpression) node).getType();
			}

			if (type != null) {
				String qualifiedName = removeArray(type).getName();
				if (qualifiedNameMatches(qualifiedName) && noArray.getEnd() > 0) {
					int start;
					int end;

					if (node instanceof ImportNode) {
						node = ((ImportNode) node).getType();
						start = node.getStart();
						end = node.getEnd();
					} else if (node instanceof ClassExpression) {
						start = node.getStart();
						end = start + ((ClassExpression) node).getType().getNameWithoutPackage().length();
					} else if (node instanceof ClassNode) {
						ClassNode classNode = (ClassNode) node;
						if (classNode.getNameEnd() > 0) {
							// we are actually dealing with a declaration
							start = classNode.getNameStart();
							end = classNode.getNameEnd() + 1;
						} else if (classNode.redirect() == classNode) {
							// this is a script declaration... ignore
							start = end = -1;
						} else {
							classNode = maybeGetComponentType(classNode);
							start = classNode.getStart();
							// sometimes the end is off by one
							end = start + classNode.getNameWithoutPackage().length();
						}
					} else if (node instanceof ConstructorNode) {
						start = ((ConstructorNode) node).getNameStart();
						end = ((ConstructorNode) node).getNameEnd() + 1;
						if (start == 0 && end == 1) {
							// synthetic constructor from script
							start = end = -1;
						}
					} else {
						start = node.getStart();
						end = node.getEnd();
					}

					if (start >= 0 && end >= 0) {
						SearchMatch match = new SearchMatch(enclosingElement, getAccuracy(result.confidence), start, end - start,
								participant, enclosingElement.getResource());
						try {
							requestor.acceptSearchMatch(match);
						} catch (CoreException e) {
							Util.log(e, "Error accepting search match for " + enclosingElement); //$NON-NLS-1$
						}
					}
				}
			}
		}
		return VisitStatus.CONTINUE;
	}

	/**
	 * sometimes the underlying component type contains the source location, not the array type
	 * 
	 * @param orig the original class node
	 * @return the component type if that type contains source information, otherwise return the original
	 */
	private ClassNode maybeGetComponentType(ClassNode orig) {
		if (orig.getComponentType() != null) {
			ClassNode componentType = orig.getComponentType();
			if (componentType.getColumnNumber() != -1) {
				return componentType;
			}
		}
		return orig;
	}

	/**
	 * @param declaration
	 * @return
	 */
	private ClassNode removeArray(ClassNode declaration) {
		return declaration.getComponentType() != null ? removeArray(declaration.getComponentType()) : declaration;
	}

	private boolean qualifiedNameMatches(String qualifiedName) {
		String newName = qualifiedName;
		if (isCaseSensitive && !isCamelCase) {
			newName = newName.toLowerCase();
		}
		// don't do * matching or camel case matching yet
		if (qualifiedName.equals(qualifier + simpleName)) {
			return true;
		}
		return false;
	}

	private int getAccuracy(TypeConfidence confidence) {
		switch (confidence) {
			case EXACT:
				return SearchMatch.A_ACCURATE;
			default:
				return SearchMatch.A_INACCURATE;
		}
	}

}
