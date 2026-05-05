---
title: Prompts
category: prompts
--

# Prompts

This section contains system prompts, model configurations, and prompt templates used across the Smarty platform for AI interactions.

## Overview

The Smarty platform uses carefully crafted prompts to guide AI behavior, ensure consistent responses, and maintain system safety. This section organizes all prompts by category and provides context for their usage.

## System Prompts

System prompts define the fundamental behavior and capabilities of AI agents in the Smarty ecosystem. These prompts establish the AI's role, constraints, and operational guidelines.

### Core System Prompts

- **[01-Friday System Prompt](./SYSTEM_PROMPTS/01-friday-system-prompt.md)** - Primary system prompt for general interactions
- **[02-Note Architect](./SYSTEM_PROMPTS/02-note-architect.md)** - Prompt for structured note creation
- **[03-Document Analyst](./SYSTEM_PROMPTS/03-document-analyst.md)** - Document analysis and processing
- **[04-Conversation Summarizer](./SYSTEM_PROMPTS/04-conversation-summarizer.md)** - Conversation summarization
- **[05-Title Generator](./SYSTEM_PROMPTS/05-title-generator.md)** - Automatic title generation
- **[06-Digest Engine](./SYSTEM_PROMPTS/06-digest-engine.md)** - Daily/weekly digest generation
- **[Claude 3.7 Sonnet Full System Message](./SYSTEM_PROMPTS/claude-3.7-sonnet-full-system-message-humanreadable.md)** - Advanced Claude system prompt

## Model-Specific Configurations

Different AI models require tailored prompts and configurations to optimize their performance for specific tasks.

### Claude Models

- **[Claude Code](./CLAUDE_MODELS/claude-code.md)** - Claude for coding tasks
- **[Claude Code Plan Mode](./CLAUDE_MODELS/claude-code-plan-mode.md)** - Planning and design with Claude
- **[Claude 4.1 Opus Thinking](./CLAUDE_MODELS/claude-4.1-opus-thinking.md)** - Advanced reasoning with Opus
- **[Claude 4.5 Sonnet](./CLAUDE_MODELS/claude-4.5-sonnet.md)** - Balanced performance model
- **[Claude AI Memory System](./CLAUDE_MODELS/claude-ai-memory-system.md)** - Memory and context management
- **[Claude in Chrome](./CLAUDE_MODELS/claude-in-chrome.md)** - Browser-based Claude integration
- **[Claude Sonnet 4](./CLAUDE_MODELS/claude-sonnet-4.md)** - Claude Sonnet configuration
- **[Claude.ai Injections](./CLAUDE_MODELS/claude.ai-injections.md)** - Claude API integration patterns
- **[End Conversation Tool](./CLAUDE_MODELS/end-conversation-tool.md)** - Conversation termination protocols

## File Format Prompts

Specialized prompts for handling different file formats and document types.

### Supported Formats

- **[PDF](./FILE_FORMATS/pdf.md)** - PDF document processing
- **[DOCX](./FILE_FORMATS/docx.md)** - Microsoft Word documents
- **[PPTX](./FILE_FORMATS/pptx.md)** - PowerPoint presentations
- **[XLSX](./FILE_FORMATS/xlsx.md)** - Excel spreadsheets
- **[Calude Code CLI Tools](./FILE_FORMATS/calude_code_cli_tools.md)** - Command-line tool integration

## Prompt Engineering Guidelines

### Best Practices

#### Clarity and Specificity
- Use clear, unambiguous language
- Define roles and constraints explicitly
- Provide concrete examples when possible
- Avoid vague or subjective terms

#### Context Management
- Establish relevant context upfront
- Define scope and boundaries
- Include necessary background information
- Set expectations for output format

#### Safety and Ethics
- Include ethical guidelines
- Define content boundaries
- Establish safety checks
- Provide escalation paths

#### Consistency
- Use standardized templates
- Maintain consistent terminology
- Follow established patterns
- Document deviations

### Prompt Structure

```
[Role Definition]
Define who the AI is and its primary function

[Context]
Provide relevant background and constraints

[Tasks]
List specific tasks and responsibilities

[Output Format]
Specify expected format and structure

[Constraints]
Define limitations and boundaries

[Examples]
Provide concrete examples when helpful

[Safety Guidelines]
Include ethical and safety considerations
```

## Prompt Categories

### Research Prompts
- Deep research initiation
- Source collection and evaluation
- Citation generation
- Synthesis and summarization

### Content Creation
- Note creation and structuring
- Document analysis
- Title generation
- Digest creation

### Conversation Management
- Conversation summarization
- Context extraction
- Intent recognition
- Response generation

### Technical Tasks
- Code generation and review
- Document processing
- Data extraction
- Analysis and reasoning

## Prompt Versioning

### Version Control
- All prompts are versioned
- Changes are documented
- Backward compatibility maintained when possible
- Deprecation notices provided

### Testing and Validation
- Prompts tested before deployment
- A/B testing for optimization
- Performance metrics tracked
- User feedback incorporated

## Integration Guidelines

### Using System Prompts

```kotlin
val prompt = SystemPromptRepository.get("01-friday-system-prompt")
val messages = listOf(
    Message(role = "system", content = prompt.content),
    Message(role = "user", content = userInput)
)

val response = aiService.generate(messages)
```

### Model Selection

```kotlin
val model = when (task.type) {
    TaskType.CODING -> Model.CLAUDE_CODE
    TaskType.RESEARCH -> Model.CLAUDE_SONNET
    TaskType.ANALYSIS -> Model.CLAUDE_OPUS
    else -> Model.DEFAULT
}
```

### Parameter Tuning

- **Temperature**: 0.7 for creative tasks, 0.3 for factual
- **Max Tokens**: Based on expected output length
- **Top P**: 0.9 for diverse outputs
- **Frequency Penalty**: 0.1 to reduce repetition

## Quality Assurance

### Prompt Testing

#### Unit Tests
- Prompt parsing and validation
- Template variable substitution
- Output format verification

#### Integration Tests
- End-to-end prompt execution
- Model response validation
- Error handling verification

#### Performance Tests
- Response time measurement
- Token usage optimization
- Cost efficiency analysis

### Monitoring

#### Key Metrics
- Prompt success rate
- Token efficiency
- Response quality scores
- User satisfaction ratings
- Cost per interaction

## Security Considerations

### Prompt Injection Prevention
- Input sanitization
- Context isolation
- Output validation
- Rate limiting

### Content Safety
- Pre-generation filtering
- Post-generation validation
- Sensitive content detection
- Ethical guideline enforcement

## Optimization Strategies

### Token Efficiency
- Concise prompt writing
- Remove redundant information
- Use abbreviations consistently
- Cache common prompts

### Performance Optimization
- Prompt pre-processing
- Template compilation
- Batch processing
- Async generation

### Cost Management
- Model selection optimization
- Token usage monitoring
- Caching strategies
- Batch processing

## Documentation Standards

### Prompt Documentation

Each prompt should include:
- **Purpose**: What the prompt accomplishes
- **Usage**: When and how to use it
- **Parameters**: Configurable options
- **Examples**: Sample inputs and outputs
- **Version**: Current version and history
- **Author**: Creator and maintainers
- **Last Updated**: Modification timestamp

### Change Log

Maintain a change log for each prompt:
- Version number
- Date of change
- Description of changes
- Reason for changes
- Impact assessment

## Troubleshooting

### Common Issues

#### Poor Response Quality
- Review prompt clarity
- Check context completeness
- Verify constraints are appropriate
- Test with different models

#### Token Overuse
- Optimize prompt length
- Reduce unnecessary context
- Implement token limits
- Use more efficient models

#### Inconsistent Outputs
- Standardize prompt format
- Add more specific constraints
- Include more examples
- Adjust temperature parameter

#### Safety Violations
- Review safety guidelines
- Add content filters
- Implement validation steps
- Escalate for review

## Resources

### Prompt Library
- **[System Prompts](./SYSTEM_PROMPTS/)** - Core system prompts
- **[Claude Models](./CLAUDE_MODELS/)** - Claude-specific configurations
- **[File Formats](./FILE_FORMATS/)** - Format-specific prompts
- **[Index](./INDEX.md)** - Complete prompt index

### Related Documentation
- **[Agent Architecture](./../02-architecture/AI_AGENT_ARCHITECTURE.md)** - Agent system design
- **[Agent Integration Guide](./../03-development/AGENT_INTEGRATION_GUIDE.md)** - Integration patterns
- **[API Documentation](./../README.md#api-endpoints)** - API specifications

## Best Practices Summary

1. **Be Specific**: Clear, unambiguous instructions
2. **Provide Context**: Relevant background information
3. **Set Boundaries**: Explicit constraints and limitations
4. **Include Examples**: Concrete examples when helpful
5. **Test Thoroughly**: Validate before deployment
6. **Monitor Continuously**: Track performance and quality
7. **Iterate Regularly**: Improve based on feedback
8. **Document Changes**: Maintain version history

---

**Version 6.0.0** | **Last Updated:** 2026-05-03