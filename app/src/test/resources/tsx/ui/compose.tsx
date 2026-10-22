"ui";

const count = compose.state(0);

ui.layout(
    <vertical padding="16">
        <text id="title" text="Header" />
        <compose id="panel" w="*" />
        <compose id="compose1" w="*" />
    </vertical>
);

compose.attach((ui as any).panel, () => (
    <compose.Column padding={16}>
        <compose.Text text="Panel" />
        <compose.Button onClick={() => { count.value++; }}>
            <compose.Text text="Add" />
        </compose.Button>
    </compose.Column>
));

compose.attach((ui as any).compose1, () => {
    return (
        <compose.Column padding={16} spacing={8}>
            <compose.Text text='Compose' />
        </compose.Column>
    );
});

const slot = ui.findView('panel');
compose.attach(slot, <compose.Text text="By variable" />);
