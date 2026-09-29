package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.persistence.UmlAttributeRepository;
import org.springframework.stereotype.Component;

@Component
public class AttributeRemoveHandler implements CommandHandler {

    private final CommandHandlerSupport support;
    private final UmlAttributeRepository attributeRepository;

    public AttributeRemoveHandler(CommandHandlerSupport support, UmlAttributeRepository attributeRepository) {
        this.support = support;
        this.attributeRepository = attributeRepository;
    }

    @Override
    public String type() {
        return "attribute.remove";
    }

    @Override
    public void apply(Context context) {
        support.noPayload(context);
        attributeRepository.delete(support.attribute(context));
    }
}
