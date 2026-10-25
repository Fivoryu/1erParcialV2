package com.diagramador.command.handler;

import com.diagramador.command.CommandHandler;
import com.diagramador.domain.TargetType;
import com.diagramador.domain.UmlAttribute;
import com.diagramador.persistence.UmlAttributeRepository;
import org.springframework.stereotype.Component;

@Component
public class AttributeRemoveHandler implements CommandHandler.DeleteAction {

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
    public Outcome apply(Context context) {
        support.noPayload(context);
        UmlAttribute attribute = support.attribute(context);
        attributeRepository.delete(attribute);
        return Outcome.deleted(TargetType.ATTRIBUTE, attribute.getId());
    }
}
